package dev.faultline.cli.relay;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import dev.faultline.cli.fault.Faults;
import dev.faultline.cli.plan.Plan;

/**
 * Sits between two topics during a run: reads what the app publishes to {@code from} and forwards it to {@code to},
 * keeping each message's key, and fires the plan's messaging fault on the way. Without a fault it just forwards, so
 * baseline runs see the same path. Each run uses a new consumer group that starts at the end of the topic.
 */
public final class ChaosRelay implements AutoCloseable {
    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration ASSIGN_TIMEOUT = Duration.ofSeconds(30);

    private final Plan.Relay relay;
    private final String group = "faultline-relay-" + UUID.randomUUID();
    private final Chaos chaos;
    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;
    private final Consumer<String> print;
    private volatile boolean running = true;
    private volatile Exception failure;
    private Thread thread;

    public ChaosRelay(String bootstrap, Plan.Relay relay, Plan.Fault fault, String idField, Path runDir, Consumer<String> print) {
        this.relay = relay;
        this.print = print;
        boolean messaging = fault != null && "messaging".equals(fault.layer());
        this.chaos = new Chaos(messaging ? fault.action() : null, messaging ? fault.nthHit() : 1, messaging ? fault.count() : 0, idField,
            (action, id) -> {
                try {
                    Faults.logFired(runDir, "messaging", action, id);
                } catch (Exception e) {
                    failure = e;
                }
            });
        this.consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            new StringDeserializer(), new StringDeserializer());
        this.producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
            new StringSerializer(), new StringSerializer());
    }

    /** The relay's consumer group, for reading its lag. */
    public String group() {
        return group;
    }

    /** Joins the topic at its end, then forwards in the background. Call it before the traffic starts. */
    public void start() {
        consumer.subscribe(List.of(relay.from()));
        long deadline = System.nanoTime() + ASSIGN_TIMEOUT.toNanos();
        while (consumer.assignment().isEmpty()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("the relay got no partitions of " + relay.from());
            consumer.poll(POLL);
        }
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);   // fix the start now, before any traffic
        consumer.commitSync();
        thread = Thread.ofPlatform().name("faultline-relay").daemon().start(this::forward);
        print.accept("relay     forwarding %s -> %s".formatted(relay.from(), relay.to()));
    }

    private void forward() {
        try {
            while (running) {
                var records = consumer.poll(POLL);
                var out = new ArrayList<Chaos.Message>();
                for (var r : records) out.addAll(chaos.accept(new Chaos.Message(r.key(), r.value())));
                if (records.isEmpty()) out.addAll(chaos.flush());
                for (var m : out) producer.send(new ProducerRecord<>(relay.to(), m.key(), m.value())).get();
                if (!records.isEmpty()) consumer.commitSync();
            }
        } catch (Exception e) {
            if (running) {
                failure = e;
                print.accept("relay     stopped: " + e.getMessage());
            }
        }
    }

    /** Disarmed, the relay only forwards; armed, it fires the plan's messaging fault (the default). */
    public void armed(boolean armed) {
        chaos.armed(armed);
    }

    /** Request IDs whose message the relay corrupted on purpose. */
    public Set<String> corrupted() {
        return chaos.corrupted();
    }

    /** Throws if the relay stopped forwarding. */
    public void checkHealthy() throws Exception {
        if (failure != null) throw new IllegalStateException("the chaos relay failed", failure);
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        if (thread != null) thread.join(Duration.ofSeconds(5));
        consumer.close(CloseOptions.timeout(Duration.ofSeconds(5)));
        producer.close(Duration.ofSeconds(5));
    }
}
