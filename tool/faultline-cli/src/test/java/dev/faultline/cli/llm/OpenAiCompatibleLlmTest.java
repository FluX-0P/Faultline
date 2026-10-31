package dev.faultline.cli.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.faultline.cli.Json;
import dev.faultline.cli.llm.Llm.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Against a fake provider on localhost, so no key and no network are needed. */
class OpenAiCompatibleLlmTest {
    static final Schema SCHEMA = new Schema("review", Json.MAPPER.readTree("""
        {"type":"object","properties":{"duplicateCheck":{"type":"string"}},"required":["duplicateCheck"],"additionalProperties":false}"""));
    private static final String OK_REPLY = """
        {"choices":[{"message":{"role":"assistant","content":"{\\"duplicateCheck\\":\\"no\\"}"}}]}""";

    private record Reply(int status, String body, String retryAfter) {}

    private HttpServer server;
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(Json.MAPPER.readTree(exchange.getRequestBody()));
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        Reply reply = replies.poll();
        if (reply.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private OpenAiCompatibleLlm llm() {
        var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");   // no trailing slash on purpose
        return new OpenAiCompatibleLlm(base, "secret-key", "test-model", log::add, sleeps::add);
    }

    @Test
    void sendsTheMessagesAndSchemaAndReturnsTheContent() {
        replies.add(new Reply(200, OK_REPLY, null));

        String content = llm().reply(List.of(Message.system("be brief"), Message.user("hello")), SCHEMA);

        assertThat(content).isEqualTo("{\"duplicateCheck\":\"no\"}");
        assertThat(authHeaders).containsExactly("Bearer secret-key");
        JsonNode sent = requests.getFirst();
        assertThat(sent.path("model").asString()).isEqualTo("test-model");
        assertThat(sent.path("messages").path(1).path("content").asString()).isEqualTo("hello");
        assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_schema");
        assertThat(sent.path("response_format").path("json_schema").path("name").asString()).isEqualTo("review");
        assertThat(sent.path("response_format").path("json_schema").path("schema")).isEqualTo(SCHEMA.json());
    }

    @Test
    void waitsAsToldWhenRateLimitedThenTriesAgain() {
        replies.add(new Reply(429, "{\"error\":{\"message\":\"slow down\"}}", "7"));
        replies.add(new Reply(429, "[{\"error\":{\"details\":[{\"retryDelay\":\"12s\"}]}}]", null));
        replies.add(new Reply(200, OK_REPLY, null));

        assertThat(llm().reply(List.of(Message.user("hi")), SCHEMA)).contains("duplicateCheck");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(12));
        assertThat(log).hasSize(2).allMatch(line -> line.startsWith("rate limited"));
    }

    @Test
    void stopsAtOnceWhenTheDailyLimitIsUsedUp() {
        replies.add(new Reply(429, "{\"error\":{\"details\":[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}}", null));

        assertThatThrownBy(() -> llm().reply(List.of(Message.user("hi")), SCHEMA)).hasMessageContaining("free daily limit for test-model");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void explainsABadKeyAndAnUnknownModel() {
        replies.add(new Reply(401, "{}", null));
        assertThatThrownBy(() -> llm().reply(List.of(Message.user("hi")), SCHEMA)).hasMessageContaining("rejected the API key");

        replies.add(new Reply(404, "{}", null));
        assertThatThrownBy(() -> llm().reply(List.of(Message.user("hi")), SCHEMA)).hasMessageContaining("does not know model 'test-model'");
    }

    @Test
    void givesUpAfterTheLastRetry() {
        for (int i = 0; i <= OpenAiCompatibleLlm.MAX_RETRIES; i++) replies.add(new Reply(503, "busy", "0"));

        assertThatThrownBy(() -> llm().reply(List.of(Message.user("hi")), SCHEMA)).hasMessageContaining("did not answer after 4 tries");
        assertThat(sleeps).hasSize(OpenAiCompatibleLlm.MAX_RETRIES);
    }
}
