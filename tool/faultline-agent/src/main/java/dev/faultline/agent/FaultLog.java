package dev.faultline.agent;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** faults.jsonl: one line per fault that fired, shared by every life of the app during a run. */
final class FaultLog {
    private FaultLog() {}

    static int count(Path log) throws IOException {
        if (!Files.exists(log)) return 0;
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return (int) lines.filter(line -> !line.isBlank()).count();
        }
    }

    /**
     * Appends one line and forces it to disk before returning, because the next thing that happens is the
     * process dying. Returns false if it could not write, and the caller then must not crash: an unrecorded
     * crash would repeat on every restart.
     */
    static boolean record(Path log, String point, String action, long hit) {
        String line = "{\"point\":\"%s\",\"action\":\"%s\",\"hit\":%d,\"pid\":%d,\"at\":\"%s\"}\n"
            .formatted(point, action, hit, ProcessHandle.current().pid(), Instant.now());
        try (var channel = FileChannel.open(log, CREATE, WRITE, APPEND)) {
            channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            return true;
        } catch (IOException e) {
            FaultAgent.log("could not write " + log + " (" + e + "), skipping this crash");
            return false;
        }
    }
}
