package dev.faultline.cli.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import dev.faultline.cli.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AskerTest {
    static final Schema SCHEMA = new Schema("review", Json.MAPPER.readTree("""
        {"type":"object","properties":{"duplicateCheck":{"type":"string"}},"required":["duplicateCheck"],"additionalProperties":false}"""));

    record Answer(String duplicateCheck) {}

    /** Replies from a script, and remembers every conversation it was sent. */
    static final class ScriptedLlm implements Llm {
        final ArrayDeque<String> replies;
        final List<List<Message>> calls = new ArrayList<>();

        ScriptedLlm(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public String reply(List<Message> messages, Schema schema) {
            calls.add(List.copyOf(messages));
            return replies.removeFirst();
        }

        @Override
        public String model() {
            return "scripted";
        }
    }

    private static List<String> mustBeYesOrNo(Answer a) {
        return List.of("yes", "no").contains(a.duplicateCheck()) ? List.of() : List.of("duplicateCheck must be yes or no");
    }

    @Test
    void returnsAGoodAnswerAndCachesIt(@TempDir Path dir) {
        var llm = new ScriptedLlm("{\"duplicateCheck\":\"no\"}");
        var asker = new Asker(llm, new LlmCache(dir), line -> {});

        var first = asker.ask("system", "question", SCHEMA, Answer.class, AskerTest::mustBeYesOrNo);
        var second = asker.ask("system", "question", SCHEMA, Answer.class, AskerTest::mustBeYesOrNo);

        assertThat(first.value().duplicateCheck()).isEqualTo("no");
        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.value()).isEqualTo(first.value());
        assertThat(llm.calls).hasSize(1);
    }

    @Test
    void aChangedQuestionIsNotACacheHit(@TempDir Path dir) {
        var llm = new ScriptedLlm("{\"duplicateCheck\":\"no\"}", "{\"duplicateCheck\":\"yes\"}");
        var asker = new Asker(llm, new LlmCache(dir), line -> {});

        asker.ask("system", "the app, version 1", SCHEMA, Answer.class, AskerTest::mustBeYesOrNo);
        var changed = asker.ask("system", "the app, version 2", SCHEMA, Answer.class, AskerTest::mustBeYesOrNo);

        assertThat(changed.cached()).isFalse();
        assertThat(changed.value().duplicateCheck()).isEqualTo("yes");
    }

    @Test
    void tellsTheLlmWhatIsWrongAndAsksOnce(@TempDir Path dir) {
        var llm = new ScriptedLlm("{\"duplicateCheck\":\"maybe\"}", "{\"duplicateCheck\":\"no\"}");
        var log = new ArrayList<String>();

        var answer = new Asker(llm, new LlmCache(dir), log::add).ask("system", "question", SCHEMA, Answer.class, AskerTest::mustBeYesOrNo);

        assertThat(answer.value().duplicateCheck()).isEqualTo("no");
        var retry = llm.calls.get(1);
        assertThat(retry).hasSize(4);
        assertThat(retry.get(2)).isEqualTo(Llm.Message.assistant("{\"duplicateCheck\":\"maybe\"}"));
        assertThat(retry.get(3).content()).contains("duplicateCheck must be yes or no");
        assertThat(log).singleElement().asString().contains("asking again");
    }

    @Test
    void failsWhenTheSecondAnswerIsStillWrong(@TempDir Path dir) {
        var llm = new ScriptedLlm("not json", "{\"duplicateCheck\":\"perhaps\"}");

        assertThatThrownBy(() -> new Asker(llm, new LlmCache(dir), line -> {})
            .ask("system", "question", SCHEMA, Answer.class, AskerTest::mustBeYesOrNo))
            .hasMessageContaining("still wrong after 2 tries").hasMessageContaining("duplicateCheck must be yes or no");
    }
}
