package dev.faultline.cli.relay;

import java.util.ArrayList;
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
 * or cut it in half so it is no longer valid JSON (corrupt). {@code action} null forwards everything untouched.
 */
final class Chaos {
    record Message(String key, String value) {}

    private final String action;
    private final long nthHit;
    private final int times;
    private final String idField;
    private final BiConsumer<String, String> fired;
    private final Map<String, Message> held = new LinkedHashMap<>();
    private final Set<String> corrupted = new LinkedHashSet<>();
    private long hits;
    private int done;

    /** @param fired told (action, request ID) each time a fault fires */
    Chaos(String action, long nthHit, int times, String idField, BiConsumer<String, String> fired) {
        this.action = action;
        this.nthHit = nthHit;
        this.times = times;
        this.idField = idField;
        this.fired = fired;
    }

    /** The messages to forward now, in order. */
    List<Message> accept(Message m) {
        var out = new ArrayList<Message>();
        Message waiting = held.remove(m.key());
        boolean fire = action != null && ++hits % nthHit == 0 && done < times;
        if (!fire) {
            out.add(m);
        } else {
            done++;
            fired.accept(action, idOf(m));
            switch (action) {
                case "duplicate" -> {
                    out.add(m);
                    out.add(m);
                }
                case "corrupt" -> {
                    corrupted.add(idOf(m));
                    out.add(new Message(m.key(), m.value().substring(0, m.value().length() / 2)));
                }
                case "reorder" -> held.put(m.key(), m);   // goes out after the next message with this key
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

    private String idOf(Message m) {
        try {
            return Workload.idOf(m.value(), idField);
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }
}
