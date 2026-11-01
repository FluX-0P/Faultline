package dev.faultline.cli.check;

import java.util.Set;

/**
 * What a run's fault destroyed on purpose, so no-lost does not blame the app for it: the requests of the messages the
 * relay corrupted, and how many corrupted messages named no request Faultline could find (binary ones, such as Avro,
 * whose bytes carry none of the run's IDs).
 */
public record Destroyed(Set<String> ids, int unreadable) {
    public static final Destroyed NONE = new Destroyed(Set.of(), 0);

    public Destroyed {
        ids = Set.copyOf(ids);
    }
}
