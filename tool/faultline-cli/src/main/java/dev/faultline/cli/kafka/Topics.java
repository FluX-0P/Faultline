package dev.faultline.cli.kafka;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/** The broker's topics and consumer groups, and topics Faultline makes itself when the broker does not create them on use. */
public final class Topics implements AutoCloseable {
    private static final long TIMEOUT_SECONDS = 10;

    private final Admin admin;

    public Topics(String bootstrapServers) {
        this.admin = Admin.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }

    /** Every topic but Kafka's own (__consumer_offsets), with its partition count, by name. */
    public Map<String, Integer> partitions() throws Exception {
        var names = admin.listTopics().names().get(TIMEOUT_SECONDS, SECONDS).stream().filter(n -> !n.startsWith("__")).toList();
        var partitions = new TreeMap<String, Integer>();
        if (names.isEmpty()) return partitions;
        admin.describeTopics(names).allTopicNames().get(TIMEOUT_SECONDS, SECONDS)
            .forEach((name, description) -> partitions.put(name, description.partitions().size()));
        return partitions;
    }

    /**
     * The consumer groups, without Faultline's own (the relay's), by name. Groups are listed unfiltered and sorted out
     * here, since an older broker cannot filter them by type (Kafka Connect's workers have groups too).
     */
    public List<String> groups() throws Exception {
        return admin.listGroups().all().get(TIMEOUT_SECONDS, SECONDS).stream()
            .filter(g -> g.protocol().isEmpty() || g.protocol().equals("consumer"))
            .map(GroupListing::groupId).filter(g -> !g.startsWith("faultline-")).sorted().toList();
    }

    /**
     * Makes {@code topic} with as many partitions as {@code like}, and the same replication, unless it exists already,
     * so a broker that does not create topics on use still has it. Returns its partition count when it made it, else 0.
     */
    public int createLike(String topic, String like) throws Exception {
        if (describe(topic) != null) return 0;
        TopicDescription model = describe(like);
        if (model == null) return 0;
        int partitions = model.partitions().size();
        short replicas = (short) model.partitions().getFirst().replicas().size();
        try {
            admin.createTopics(List.of(new NewTopic(topic, partitions, replicas))).all().get(TIMEOUT_SECONDS, SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) return 0;
            throw e;
        }
        return partitions;
    }

    private TopicDescription describe(String topic) throws Exception {
        try {
            return admin.describeTopics(List.of(topic)).allTopicNames().get(TIMEOUT_SECONDS, SECONDS).get(topic);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) return null;
            throw e;
        }
    }

    @Override
    public void close() {
        admin.close(Duration.ofSeconds(5));
    }
}
