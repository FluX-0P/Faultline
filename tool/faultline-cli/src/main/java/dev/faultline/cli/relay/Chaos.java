package dev.faultline.cli.relay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import dev.faultline.cli.workload.Workload;

/**
 * What the chaos relay does to each message, without any Kafka: forward it, or on every nth message (until it has done
 * so {@code times} times) duplicate it, hold it back until the next message with the same key has gone past (reorder),
 * or cut it in half so it no longer decodes, as JSON or as Avro (corrupt). {@code action} null forwards everything
 * untouched. Messages are bytes: the relay never needs their schema.
 */
final class Chaos {
    /** {@code where}: partition@offset, to name a message whose request ID cannot be read. */
    record Message(byte[] key, byte[] value, List<Header> headers, String where) {
        Message(String key, String value) {
            this(key == null ? null : key.getBytes(StandardCharsets.UTF_8), value == null ? null : value.getBytes(StandardCharsets.UTF_8), List.of(), "?");
        }

        String text() {
            return value == null ? "" : new String(value, StandardCharsets.UTF_8);
        }
    }

    record Header(String key, byte[] value) {}

    private final String action;
    private final long nthHit;
    private final int times;
    private final String idField;
    private final BiConsumer<String, String> fired;
    private final Map<String, Message> held = new LinkedHashMap<>();
    private final Set<String> corrupted = new LinkedHashSet<>();
    private final List<Message> unreadable = new ArrayList<>();
    private long hits;
    private int done;
    private volatile boolean armed = true;

    /** @param fired told (action, request ID) each time a fault fires */
    Chaos(String action, long nthHit, int times, String idField, BiConsumer<String, String> fired) {
        this.action = action;
        this.nthHit = nthHit;
        this.times = times;
        this.idField = idField;
        this.fired = fired;
    }

    /** Disarmed, every message goes through untouched and uncounted, such as during a warm-up. */
    void armed(boolean armed) {
        this.armed = armed;
    }

    /** The messages to forward now, in order. */
    List<Message> accept(Message m) {
        var out = new ArrayList<Message>();
        Message waiting = held.remove(heldKey(m));
        boolean fire = action != null && armed && ++hits % nthHit == 0 && done < times;
        if (!fire) {
            out.add(m);
        } else {
            done++;
            String id = idOf(m);
            fired.accept(action, id != null ? id : "the message at " + m.where());
            switch (action) {
                case "duplicate" -> {
                    out.add(m);
                    out.add(m);
                }
                case "corrupt" -> {
                    if (id != null) corrupted.add(id);
                    else unreadable.add(m);
                    out.add(new Message(m.key(), m.value() == null ? null : Arrays.copyOf(m.value(), m.value().length / 2), m.headers(), m.where()));
                }
                case "reorder" -> held.put(heldKey(m), m);   // goes out after the next message with this key
                default -> throw new IllegalArgumentException("unknown messaging fault " + action);
            }
        }
        if (waiting != null) out.add(waiting);
        return out;
    }

    /** Messages still held back, for when the traffic has gone quiet. */
    List<Message> flush() {
        var out = new ArrayList<>(held.values());
        held.clear();
        return out;
    }

    /** Request IDs whose message was corrupted on purpose: the app could never apply them. */
    Set<String> corrupted() {
        return Set.copyOf(corrupted);
    }

    /** Corrupted messages whose request ID was not readable as JSON, as they were before: binary, such as Avro. */
    List<Message> unreadable() {
        return List.copyOf(unreadable);
    }

    /** The key as text, so two messages with the same key bytes are held as one key. */
    private static String heldKey(Message m) {
        return m.key() == null ? "" : new String(m.key(), StandardCharsets.ISO_8859_1);
    }

    /** The request ID in a JSON message; null for one that is not JSON or does not name it. */
    private String idOf(Message m) {
        try {
            return Workload.idOf(m.text(), idField);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
