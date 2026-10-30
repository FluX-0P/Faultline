package dev.faultline.cli.kafka;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toMap;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/**
 * How far a consumer group is behind on a topic: messages on the topic it has not acknowledged yet.
 * After a crash the unacknowledged message keeps the lag above zero until it is redelivered and handled,
 * so "lag is zero" means the app has really finished, however long a restart took.
 */
public final class KafkaLag implements AutoCloseable {
    private static final long TIMEOUT_SECONDS = 10;

    private final Admin admin;
    private final String group;
    private final String topic;

    public KafkaLag(String bootstrapServers, String group, String topic) {
        this.admin = Admin.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
        this.group = group;
        this.topic = topic;
    }

    /** Messages not yet acknowledged by the group. 0 if the topic does not exist yet. */
    public long lag() throws Exception {
        Map<TopicPartition, Long> ends = endOffsets();
        if (ends.isEmpty()) return 0;
        Map<TopicPartition, OffsetAndMetadata> committed = committed();
        Map<TopicPartition, Long> starts = null;
        long lag = 0;
        for (var end : ends.entrySet()) {
            var c = committed.get(end.getKey());
            long position;
            if (c != null) {
                position = c.offset();
            } else {
                // never committed: the app starts from the beginning (auto-offset-reset: earliest)
                if (starts == null) starts = offsets(ends.keySet(), OffsetSpec.earliest());
                position = starts.get(end.getKey());
            }
            lag += Math.max(0, end.getValue() - position);
        }
        return lag;
    }

    /**
     * Moves the group to the end of the topic, so leftovers from an earlier, aborted run are never processed.
     * Kafka only allows this while no consumer in the group is running. Returns how many messages were skipped.
     */
    public long skipBacklog() throws Exception {
        long lag = lag();
        if (lag == 0) return 0;
        var toEnd = endOffsets().entrySet().stream().collect(toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue())));
        try {
            admin.alterConsumerGroupOffsets(group, toEnd).all().get(TIMEOUT_SECONDS, SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("could not skip " + lag + " leftover messages: is another consumer in group '"
                + group + "' still running?", e);
        }
        return lag;
    }

    private Map<TopicPartition, Long> endOffsets() throws Exception {
        var partitions = partitions();
        return partitions.isEmpty() ? Map.of() : offsets(partitions, OffsetSpec.latest());
    }

    private List<TopicPartition> partitions() throws Exception {
        try {
            var description = admin.describeTopics(List.of(topic)).allTopicNames().get(TIMEOUT_SECONDS, SECONDS).get(topic);
            return description.partitions().stream().map(p -> new TopicPartition(topic, p.partition())).toList();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) return List.of();
            throw e;
        }
    }

    private Map<TopicPartition, Long> offsets(Collection<TopicPartition> partitions, OffsetSpec spec) throws Exception {
        var request = partitions.stream().collect(toMap(p -> p, p -> spec));
        return admin.listOffsets(request).all().get(TIMEOUT_SECONDS, SECONDS).entrySet().stream()
            .collect(toMap(Map.Entry::getKey, e -> e.getValue().offset()));
    }

    private Map<TopicPartition, OffsetAndMetadata> committed() throws Exception {
        Map<TopicPartition, OffsetAndMetadata> all;
        try {
            all = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GroupIdNotFoundException) return Map.of();
            throw e;
        }
        var forTopic = new HashMap<TopicPartition, OffsetAndMetadata>();
        all.forEach((partition, offset) -> {
            if (offset != null && partition.topic().equals(topic)) forTopic.put(partition, offset);
        });
        return forTopic;
    }

    @Override
    public void close() {
        admin.close(Duration.ofSeconds(5));
    }
}
