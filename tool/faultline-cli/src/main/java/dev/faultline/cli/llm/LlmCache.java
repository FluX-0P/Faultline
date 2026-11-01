package dev.faultline.cli.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import dev.faultline.cli.Json;
import dev.faultline.cli.llm.Llm.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Good LLM answers on disk, one file per question: {sha-256 of model, schema and prompt}.json. The prompt holds the
 * app's files, so an unchanged app costs no new call, and any change to its files asks again. Each file also keeps
 * the prompt, for reading what the LLM was actually told. Delete the folder to start fresh.
 */
public final class LlmCache {
    private final Path dir;

    public LlmCache(Path dir) {
        this.dir = dir;
    }

    public static String key(String model, Schema schema, List<Message> messages) {
        // a record, not a Map.of: its fields always serialize in the same order, so the same question gets the same key
        record Question(String model, JsonNode schema, List<Message> messages) {}
        String question = Json.MAPPER.writeValueAsString(new Question(model, schema.json(), messages));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(question.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> get(String key) {
        Path file = dir.resolve(key + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            var answer = Json.MAPPER.readTree(file.toFile()).path("answer");
            return answer.isString() ? Optional.of(answer.asString()) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();   // a damaged entry is just a miss
        }
    }

    public void put(String key, String model, List<Message> messages, String answer) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        record Entry(String model, Instant at, List<Message> messages, String answer) {}
        Json.writePretty(dir.resolve(key + ".json"), new Entry(model, Instant.now(), messages, answer));
    }

    /**
     * Keeps a question whose answers stayed wrong, with every answer and what was wrong with it, in failed/: never read
     * back as an answer, only for a person to see what the LLM was asked and said. Returns the file.
     */
    public Path putFailed(String key, String model, List<Message> messages, String lastAnswer) {
        Path failed = dir.resolve("failed");
        try {
            Files.createDirectories(failed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        record Entry(String model, Instant at, List<Message> messages, String answer) {}
        Path file = failed.resolve(key + ".json");
        Json.writePretty(file, new Entry(model, Instant.now(), messages, lastAnswer));
        return file;
    }
}
