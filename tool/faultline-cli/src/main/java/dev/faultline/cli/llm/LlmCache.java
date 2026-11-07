package dev.faultline.cli.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.faultline.cli.Json;
import dev.faultline.cli.llm.Llm.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Good LLM answers on disk, one file per question: {sha-256 of model, schema and prompt}.json. The prompt holds the
 * app's files, so an unchanged app costs no new call, and any change to its files asks again. Each file also keeps
 * the prompt, for reading what the LLM was actually told. Delete the folder to start fresh.
 *
 * <p>More folders can be read too, such as the benchmark's committed answers, and every answer served can be copied to
 * one, so it holds what a run used.
 */
public final class LlmCache {
    private final Path dir;
    private final List<Path> also;
    private final Path copyTo;
    /** Every entry of the folders by its question, read on the first miss: see {@link #sameQuestion}. */
    private Map<String, Path> byQuestion;

    public LlmCache(Path dir) {
        this(dir, List.of(), null);
    }

    private LlmCache(Path dir, List<Path> also, Path copyTo) {
        this.dir = dir;
        this.also = also;
        this.copyTo = copyTo;
    }

    /** Also reads answers from {@code other}, after its own folder; it writes to it only through {@link #copyingTo}. */
    public LlmCache alsoReading(Path other) {
        var dirs = new ArrayList<>(also);
        dirs.add(other);
        return new LlmCache(dir, List.copyOf(dirs), copyTo);
    }

    /** Also writes every answer it serves, found or new, to {@code folder}, so the folder holds what was used. */
    public LlmCache copyingTo(Path folder) {
        return new LlmCache(dir, also, folder);
    }

    public static String key(String model, Schema schema, List<Message> messages) {
        // a record, not a Map.of: its fields always serialize in the same order, so the same question gets the same key
        record Question(String model, JsonNode schema, List<Message> messages) {}
        String question = Json.MAPPER.writeValueAsString(new Question(model, schema.json(), messages));
        return sha256(question);
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> get(String key) {
        for (Path folder : folders()) {
            var answer = answer(folder.resolve(key + ".json"));
            if (answer.isPresent()) return answer;
        }
        return Optional.empty();
    }

    /**
     * The answer to a question, by its key, else by the question itself: an entry cached before Faultline sent every
     * line ending as \n has another key for the same question (the code it quotes had \r\n on Windows). Found that
     * way, it is cached again under its key. Copied to the {@link #copyingTo} folder either way.
     */
    public Optional<String> get(String key, String model, List<Message> messages) {
        var answer = get(key);
        if (answer.isEmpty()) {
            Path same = sameQuestion(model, messages);
            answer = same == null ? Optional.empty() : answer(same);
            answer.ifPresent(a -> write(dir, key, model, messages, a));
        }
        if (answer.isPresent() && copyTo != null && !Files.isRegularFile(copyTo.resolve(key + ".json"))) {
            write(copyTo, key, model, messages, answer.get());
        }
        return answer;
    }

    private List<Path> folders() {
        var folders = new ArrayList<Path>();
        folders.add(dir);
        folders.addAll(also);
        return folders;
    }

    private static Optional<String> answer(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            var answer = Json.MAPPER.readTree(file.toFile()).path("answer");
            return answer.isString() ? Optional.of(answer.asString()) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();   // a damaged entry is just a miss
        }
    }

    /** The entry asked of {@code model} with these messages, every line ending read as \n; null if none. */
    private Path sameQuestion(String model, List<Message> messages) {
        if (byQuestion == null) {
            byQuestion = new HashMap<>();
            // the first folder last, so its entries win
            for (Path folder : folders().reversed()) {
                if (!Files.isDirectory(folder)) continue;
                try (var files = Files.list(folder)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                        try {
                            var entry = Json.MAPPER.readTree(file.toFile());
                            var asked = new ArrayList<Message>();
                            entry.path("messages").forEach(m -> asked.add(new Message(m.path("role").asString(), m.path("content").asString())));
                            byQuestion.put(question(entry.path("model").asString(), asked), file);
                        } catch (JacksonException e) {
                            // a damaged entry is just a miss
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return byQuestion.get(question(model, messages));
    }

    /** The question as first asked, its system and user message: an answer that took a second try keeps that try too. */
    private static String question(String model, List<Message> messages) {
        var text = new StringBuilder(model);
        for (Message m : messages.subList(0, Math.min(2, messages.size()))) text.append('\u0000').append(m.role()).append('\u0000').append(m.content().replace("\r\n", "\n"));
        return sha256(text.toString());
    }

    public void put(String key, String model, List<Message> messages, String answer) {
        write(dir, key, model, messages, answer);
        if (copyTo != null) write(copyTo, key, model, messages, answer);
    }

    private static void write(Path folder, String key, String model, List<Message> messages, String answer) {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        record Entry(String model, Instant at, List<Message> messages, String answer) {}
        Json.writePretty(folder.resolve(key + ".json"), new Entry(model, Instant.now(), messages, answer));
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
