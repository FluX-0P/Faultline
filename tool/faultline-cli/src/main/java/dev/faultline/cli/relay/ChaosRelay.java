package dev.faultline.cli.relay;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import dev.faultline.cli.check.Destroyed;
import dev.faultline.cli.fault.Faults;
import dev.faultline.cli.kafka.IdsInBytes;
import dev.faultline.cli.kafka.Topics;
import dev.faultline.cli.plan.Plan;

/**
 * Sits between two topics during a run: reads what the app publishes to {@code from} and forwards it to {@code to},
 * bytes as they are, with each message's key and headers, and fires the plan's messaging fault on the way. Without a
 * fault it just forwards, so baseline runs see the same path. Each run uses a new consumer group that starts at the end
 * of the topic. JSON, Avro or Protobuf: the relay never decodes a message, except to read a JSON one's request ID.
 */
public final class ChaosRelay implements AutoCloseable {
    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration ASSIGN_TIMEOUT = Duration.ofSeconds(30);

    private final String bootstrap;
    private final Plan.Relay relay;
    private final String group = "faultline-relay-" + UUID.randomUUID();
    private final Chaos chaos;
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final KafkaProducer<byte[], byte[]> producer;
    private final Consumer<String> print;
    private volatile boolean running = true;
    private volatile Exception failure;
    private Thread thread;

    public ChaosRelay(String bootstrap, Plan.Relay relay, Plan.Fault fault, String idField, Path runDir, Consumer<String> print) {
        this.bootstrap = bootstrap;
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
            new ByteArrayDeserializer(), new ByteArrayDeserializer());
        this.producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
            new ByteArraySerializer(), new ByteArraySerializer());
    }

    /** The relay's consumer group, for reading its lag. */
    public String group() {
        return group;
    }

    /**
     * Makes the relay's topic that does not exist yet like the other: the {@code from} topic the app's sends were moved
     * to, or the {@code to} topic its listener was moved to, since a broker may not create topics on use, and one that
     * does makes them with its default partitions. Call it before the app starts, so a moved listener finds its topic.
     */
    public static void makeTopics(String bootstrap, Plan.Relay relay, Consumer<String> print) throws Exception {
        try (var topics = new Topics(bootstrap)) {
            int partitions = topics.createLike(relay.from(), relay.to());
            if (partitions > 0) print.accept("relay     made topic %s with %d partitions, like %s".formatted(relay.from(), partitions, relay.to()));
            partitions = topics.createLike(relay.to(), relay.from());
            if (partitions > 0) print.accept("relay     made topic %s with %d partitions, like %s".formatted(relay.to(), partitions, relay.from()));
        }
    }

    /** Joins the topic at its end, then forwards in the background. Call it before the traffic starts. */
    public void start() throws Exception {
        makeTopics(bootstrap, relay, print);
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
                for (var r : records) {
                    var headers = new ArrayList<Chaos.Header>();
                    r.headers().forEach(h -> headers.add(new Chaos.Header(h.key(), h.value())));
                    out.addAll(chaos.accept(new Chaos.Message(r.key(), r.value(), headers, r.partition() + "@" + r.offset())));
                }
                if (records.isEmpty()) out.addAll(chaos.flush());
                for (var m : out) {
                    var record = new ProducerRecord<>(relay.to(), m.key(), m.value());
                    m.headers().forEach(h -> record.headers().add(new RecordHeader(h.key(), h.value())));
                    producer.send(record).get();
                }
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

    /** Request IDs whose message the relay corrupted on purpose, when they are JSON. */
    public Set<String> corrupted() {
        return chaos.corrupted();
    }

    /**
     * What the relay destroyed on purpose: the requests of the messages it corrupted. A binary message's request is
     * found by the run's IDs in its key or value; one that names none of them is only counted.
     *
     * @param ids every ID of the run that a message may carry: the requests', and the ones the app answered with
     */
    public Destroyed destroyed(Collection<String> ids) {
        var found = new LinkedHashSet<>(chaos.corrupted());
        int unreadable = 0;
        var search = new IdsInBytes(ids);
        for (var m : chaos.unreadable()) {
            var named = new LinkedHashSet<>(search.in(m.value()));
            named.addAll(search.in(m.key()));
            if (named.isEmpty()) unreadable++;
            found.addAll(named);
        }
        return new Destroyed(found, unreadable);
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
