package dev.faultline.cli.history;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import dev.faultline.cli.Json;

/**
 * history.jsonl: one JSON object per line, appended as each request finishes and flushed straight away,
 * so the file stays readable even if the tool itself is killed mid-run.
 */
public final class HistoryLog implements AutoCloseable {
    private final BufferedWriter out;

    private HistoryLog(BufferedWriter out) {
        this.out = out;
    }

    public static HistoryLog create(Path file) throws IOException {
        return new HistoryLog(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
    }

    /** Called from many sender threads at once. */
    public synchronized void append(HistoryEntry entry) {
        try {
            out.write(Json.MAPPER.writeValueAsString(entry));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads every entry back, sorted by index (the file itself is in completion order). */
    public static List<HistoryEntry> read(Path file) throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                .map(line -> Json.MAPPER.readValue(line, HistoryEntry.class))
                .sorted(Comparator.comparingInt(HistoryEntry::index))
                .toList();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
