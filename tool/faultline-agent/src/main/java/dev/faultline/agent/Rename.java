package dev.faultline.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renames the topic of the app's Kafka sends, so a message the code sends to a topic it names goes to Faultline's relay
 * instead: the relay reads it there and forwards it to the topic the app meant. The agent has no Kafka of its own, so
 * a ProducerRecord is read and rebuilt through reflection, with the same partition, timestamp, key, value and headers.
 */
public final class Rename {
    private static final String RECORD = "org.apache.kafka.clients.producer.ProducerRecord";
    private static volatile Map<String, String> topics = Map.of();
    private static final Map<Class<?>, Access> ACCESS = new ConcurrentHashMap<>();
    private static volatile boolean warned;

    private record Access(Method topic, Method partition, Method timestamp, Method key, Method value, Method headers, Constructor<?> make) {}

    private Rename() {}

    static void topics(Map<String, String> renames) {
        topics = Map.copyOf(renames);
    }

    /** The record to send instead: the same one sent to the renamed topic, or the record itself. */
    public static Object of(Object record) {
        if (record == null || topics.isEmpty()) return record;
        try {
            Access a = ACCESS.computeIfAbsent(record.getClass(), Rename::access);
            String to = topics.get((String) a.topic().invoke(record));
            if (to == null) return record;
            return a.make().newInstance(to, a.partition().invoke(record), a.timestamp().invoke(record), a.key().invoke(record),
                a.value().invoke(record), a.headers().invoke(record));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                FaultAgent.log("could not rename the topic of a send, so it goes where the app sent it (" + e + ")");
            }
            return record;
        }
    }

    private static Access access(Class<?> type) {
        try {
            Class<?> record = Class.forName(RECORD, false, type.getClassLoader());
            return new Access(record.getMethod("topic"), record.getMethod("partition"), record.getMethod("timestamp"), record.getMethod("key"),
                record.getMethod("value"), record.getMethod("headers"),
                record.getConstructor(String.class, Integer.class, Long.class, Object.class, Object.class, Iterable.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
