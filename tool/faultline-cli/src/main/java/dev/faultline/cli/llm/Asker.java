package dev.faultline.cli.llm;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.config.Settings;
import dev.faultline.cli.llm.Llm.Message;
import tools.jackson.core.JacksonException;

/**
 * Asks the LLM one question and returns its answer as a Java record. The answer must parse and pass the caller's
 * own checks (for example "that handler is not in the code"); if it doesn't, the LLM is told what is wrong and asked
 * once more. Good answers are cached, so asking the same question about the same files is free.
 */
public final class Asker {
    static final int MAX_ATTEMPTS = 2;

    private final Llm llm;
    private final LlmCache cache;
    private final Consumer<String> log;

    public Asker(Llm llm, LlmCache cache, Consumer<String> log) {
        this.llm = llm;
        this.cache = cache;
        this.log = log;
    }

    public static Asker fromSettings(Consumer<String> log) {
        return new Asker(OpenAiCompatibleLlm.fromSettings(Settings.load(), log), new LlmCache(Workspace.cache()), log);
    }

    public String model() {
        return llm.model();
    }

    public record Answer<T>(T value, String model, boolean cached, Duration took) {}

    /**
     * @param problems what is wrong with a parsed answer, empty if nothing
     */
    public <T> Answer<T> ask(String system, String user, Schema schema, Class<T> type, Function<T, List<String>> problems) {
        var messages = new ArrayList<>(List.of(Message.system(system), Message.user(user)));
        String key = LlmCache.key(llm.model(), schema, messages);

        var cached = cache.get(key);
        if (cached.isPresent()) {
            var parsed = parse(cached.get(), type, problems);
            if (parsed.problems.isEmpty()) return new Answer<>(parsed.value, llm.model(), true, Duration.ZERO);
        }

        long start = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            String reply = llm.reply(messages, schema);
            var parsed = parse(reply, type, problems);
            if (parsed.problems.isEmpty()) {
                cache.put(key, llm.model(), messages, reply);
                return new Answer<>(parsed.value, llm.model(), false, Duration.ofNanos(System.nanoTime() - start));
            }
            if (attempt == MAX_ATTEMPTS) {
                Path kept = cache.putFailed(key, llm.model(), messages, reply);
                throw new IllegalStateException("the LLM's answer was still wrong after " + MAX_ATTEMPTS + " tries: "
                    + String.join("; ", parsed.problems) + " (the question and its answers are in " + Out.path(kept) + ")");
            }
            log.accept("the answer had %d problem(s), asking again: %s".formatted(parsed.problems.size(), parsed.problems.getFirst()));
            messages.add(Message.assistant(reply));
            messages.add(Message.user("That answer has problems:\n- " + String.join("\n- ", parsed.problems)
                + "\nReply again with the whole corrected JSON."));
        }
    }

    private record Parsed<T>(T value, List<String> problems) {}

    private static <T> Parsed<T> parse(String reply, Class<T> type, Function<T, List<String>> problems) {
        T value;
        try {
            value = Json.MAPPER.readValue(reply, type);
        } catch (JacksonException e) {
            return new Parsed<>(null, List.of("not valid JSON for the schema: " + e.getOriginalMessage()));
        }
        if (value == null) return new Parsed<>(null, List.of("the answer was empty"));
        return new Parsed<>(value, problems.apply(value));
    }
}
