package dev.faultline.cli.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.workload.Workload;

/**
 * Reads what the app publishes to its downstream topic during a run, from where the topic ended when the run began,
 * and keeps the request ID each event names. It joins no consumer group, so the app's own consumers never notice it.
 */
public final class DownstreamReader implements AutoCloseable {
    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration FIND_TOPIC = Duration.ofSeconds(30);

    private final Plan.Downstream downstream;
    private final KafkaConsumer<String, String> consumer;
    private final Set<String> ids = ConcurrentHashMap.newKeySet();
    private final AtomicLong events = new AtomicLong();
    private volatile boolean running = true;
    private volatile Exception failure;
    private Thread thread;

    public DownstreamReader(String bootstrap, Plan.Downstream downstream) {
        this.downstream = downstream;
        this.consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            new StringDeserializer(), new StringDeserializer());
    }

    /** Starts at the end of every partition, then reads in the background. Call it before the traffic starts. */
    public void start() throws InterruptedException {
        long deadline = System.nanoTime() + FIND_TOPIC.toNanos();
        List<TopicPartition> partitions;
        while ((partitions = partitions()).isEmpty()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("topic " + downstream.topic() + " does not exist (does the app create it?)");
            Thread.sleep(500);
        }
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);   // fix the start now, before any traffic
        thread = Thread.ofPlatform().name("faultline-downstream").daemon().start(this::read);
    }

    private List<TopicPartition> partitions() {
        var infos = consumer.partitionsFor(downstream.topic());
        return infos == null ? List.of() : infos.stream().map(p -> new TopicPartition(p.topic(), p.partition())).toList();
    }

    private void read() {
        try {
            while (running) {
                for (var r : consumer.poll(POLL)) {
                    events.incrementAndGet();
                    try {
                        ids.add(Workload.idOf(r.value(), downstream.requestField()));
                    } catch (IllegalArgumentException e) {
                        // not an event this check understands; it names no request
                    }
                }
            }
        } catch (Exception e) {
            if (running) failure = e;
        }
    }

    /** Events read so far, of any kind. */
    public long events() {
        return events.get();
    }

    /** The request IDs the events named. */
    public Set<String> requestIds() {
        return Set.copyOf(ids);
    }

    public void checkHealthy() {
        if (failure != null) throw new IllegalStateException("reading " + downstream.topic() + " failed", failure);
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        if (thread != null) thread.join(Duration.ofSeconds(5));
        consumer.close(CloseOptions.timeout(Duration.ofSeconds(5)));
    }
}
