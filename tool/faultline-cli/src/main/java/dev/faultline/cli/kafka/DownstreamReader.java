package dev.faultline.cli.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.workload.Workload;

/**
 * Reads what the app publishes to its downstream topic during a run, from where the topic ended when the run began,
 * and keeps the request ID each event names. It joins no consumer group, so the app's own consumers never notice it.
 * A JSON event names its request in the plan's field. Any other, such as Avro, is kept as bytes, and the IDs of the
 * run are looked for in it once they are all known.
 */
public final class DownstreamReader implements AutoCloseable {
    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration FIND_TOPIC = Duration.ofSeconds(30);

    private final Plan.Downstream downstream;
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final Set<String> ids = ConcurrentHashMap.newKeySet();
    /** Events that are not JSON: key and value. */
    private final Queue<byte[][]> binary = new ConcurrentLinkedQueue<>();
    private final AtomicLong events = new AtomicLong();
    private volatile boolean running = true;
    private volatile Exception failure;
    private Thread thread;

    public DownstreamReader(String bootstrap, Plan.Downstream downstream) {
        this.downstream = downstream;
        this.consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            new ByteArrayDeserializer(), new ByteArrayDeserializer());
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
                    if (!json(r.value())) {
                        binary.add(new byte[][] {r.key(), r.value()});
                        continue;
                    }
                    try {
                        ids.add(Workload.idOf(new String(r.value(), StandardCharsets.UTF_8), downstream.requestField()));
                    } catch (IllegalArgumentException e) {
                        // not an event this check understands; it names no request
                    }
                }
            }
        } catch (Exception e) {
            if (running) failure = e;
        }
    }

    /** Whether a value looks like a JSON document: its first byte that is not white space opens an object or an array. */
    static boolean json(byte[] value) {
        if (value == null) return false;
        for (byte b : value) {
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') continue;
            return b == '{' || b == '[';
        }
        return false;
    }

    /** Events read so far, of any kind. */
    public long events() {
        return events.get();
    }

    /** The request IDs the JSON events named. */
    public Set<String> requestIds() {
        return Set.copyOf(ids);
    }

    /** The request IDs the events named: the JSON ones', and those of {@code known} found in the others' bytes. */
    public Set<String> requestIds(Collection<String> known) {
        var all = new HashSet<>(ids);
        if (binary.isEmpty()) return all;
        var search = new IdsInBytes(known);
        for (var kv : binary) {
            all.addAll(search.in(kv[1]));
            all.addAll(search.in(kv[0]));
        }
        return all;
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
