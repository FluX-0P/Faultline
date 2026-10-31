package dev.faultline.cli.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import dev.faultline.cli.Json;
import dev.faultline.cli.config.Settings;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Any provider with OpenAI's chat-completions API: Google Gemini (the default), Groq, OpenRouter or a local Ollama.
 * Asks for a reply that follows a JSON schema, and waits and retries when the provider says to slow down.
 */
public final class OpenAiCompatibleLlm implements Llm {
    public static final String KEY_ENV = "FAULTLINE_LLM_API_KEY";
    public static final String BASE_URL_ENV = "FAULTLINE_LLM_BASE_URL";
    public static final String MODEL_ENV = "FAULTLINE_LLM_MODEL";
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/";
    public static final String DEFAULT_MODEL = "gemini-3.6-flash";

    static final int MAX_RETRIES = 3;
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    static final Duration DEFAULT_WAIT = Duration.ofSeconds(20);
    static final Duration MAX_WAIT = Duration.ofSeconds(90);
    // Gemini says "retryDelay": "25s" or "Please retry in 25.3s"; other providers send a Retry-After header
    private static final Pattern RETRY_DELAY = Pattern.compile("(?:retryDelay\"\\s*:\\s*\"|retry in )(\\d+(?:\\.\\d+)?)s");

    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Consumer<String> log;
    private final Sleeper sleeper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    OpenAiCompatibleLlm(URI baseUrl, String apiKey, String model, Consumer<String> log, Sleeper sleeper) {
        String base = baseUrl.toString();
        this.endpoint = URI.create(base.endsWith("/") ? base : base + "/").resolve("chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.log = log;
        this.sleeper = sleeper;
    }

    public static OpenAiCompatibleLlm fromSettings(Settings settings, Consumer<String> log) {
        String key = settings.require(KEY_ENV, "copy .env.example to .env and add your key (free at https://aistudio.google.com)");
        return new OpenAiCompatibleLlm(URI.create(settings.get(BASE_URL_ENV).orElse(DEFAULT_BASE_URL)), key,
            settings.get(MODEL_ENV).orElse(DEFAULT_MODEL), log, Thread::sleep);
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String reply(List<Message> messages, Schema schema) {
        var request = HttpRequest.newBuilder(endpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body(messages, schema))))
            .build();
        for (int attempt = 0; ; attempt++) {
            String problem;
            Duration wait = DEFAULT_WAIT.multipliedBy(attempt + 1);
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code == 200) return content(response.body());
                if (code == 401 || code == 403) {
                    throw new IllegalStateException("the LLM provider rejected the API key (HTTP " + code + "): check " + KEY_ENV);
                }
                if (code == 404) {
                    throw new IllegalStateException("the LLM provider does not know model '" + model + "' or this endpoint (HTTP 404): check "
                        + MODEL_ENV + " and " + BASE_URL_ENV);
                }
                if (code == 429 && response.body().contains("PerDay")) {
                    throw new IllegalStateException("the free daily limit for " + model + " is used up (it resets at midnight Pacific time):"
                        + " wait, or set " + MODEL_ENV + " to another model in .env");
                }
                if (code != 429 && code < 500) {
                    throw new IllegalStateException("the LLM request failed with HTTP " + code + ": " + excerpt(response.body()));
                }
                problem = code == 429 ? "rate limited" : "HTTP " + code;
                wait = retryAfter(response).orElse(wait);
            } catch (IOException e) {
                problem = e.getClass().getSimpleName();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the LLM", e);
            }
            if (attempt == MAX_RETRIES) {
                throw new IllegalStateException("the LLM did not answer after " + (MAX_RETRIES + 1) + " tries (last: " + problem + ")");
            }
            if (wait.compareTo(MAX_WAIT) > 0) wait = MAX_WAIT;
            log.accept("%s, waiting %ds and trying again (%d of %d)".formatted(problem, wait.toSeconds(), attempt + 1, MAX_RETRIES));
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the LLM", e);
            }
        }
    }

    private ObjectNode body(List<Message> messages, Schema schema) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("model", model);
        var array = body.putArray("messages");
        for (Message m : messages) {
            array.addObject().put("role", m.role()).put("content", m.content());
        }
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        format.putObject("json_schema").put("name", schema.name()).put("strict", true).set("schema", schema.json());
        return body;
    }

    private static String content(String responseBody) {
        try {
            JsonNode content = Json.MAPPER.readTree(responseBody).path("choices").path(0).path("message").path("content");
            if (content.isString() && !content.asString().isBlank()) return content.asString();
        } catch (JacksonException e) {
            // reported below
        }
        throw new IllegalStateException("the LLM sent no answer: " + excerpt(responseBody));
    }

    static Optional<Duration> retryAfter(HttpResponse<String> response) {
        var header = response.headers().firstValue("Retry-After");
        if (header.isPresent() && header.get().matches("\\d+")) return Optional.of(Duration.ofSeconds(Long.parseLong(header.get())));
        var m = RETRY_DELAY.matcher(response.body());
        if (m.find()) return Optional.of(Duration.ofMillis((long) Math.ceil(Double.parseDouble(m.group(1)) * 1000)));
        return Optional.empty();
    }

    private static String excerpt(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + " ...";
    }
}
