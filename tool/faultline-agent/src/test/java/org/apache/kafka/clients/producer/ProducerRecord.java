package org.apache.kafka.clients.producer;

/** A stand-in for Kafka's ProducerRecord, with the same constructor and accessors, so the agent's rename is tested without Kafka. */
public class ProducerRecord<K, V> {
    private final String topic;
    private final Integer partition;
    private final Long timestamp;
    private final K key;
    private final V value;
    private final Iterable<?> headers;

    public ProducerRecord(String topic, Integer partition, Long timestamp, K key, V value, Iterable<?> headers) {
        this.topic = topic;
        this.partition = partition;
        this.timestamp = timestamp;
        this.key = key;
        this.value = value;
        this.headers = headers;
    }

    public String topic() {
        return topic;
    }

    public Integer partition() {
        return partition;
    }

    public Long timestamp() {
        return timestamp;
    }

    public K key() {
        return key;
    }

    public V value() {
        return value;
    }

    public Iterable<?> headers() {
        return headers;
    }
}
