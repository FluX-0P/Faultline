package dev.faultline.cli.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.config.SettingsAccess;
import dev.faultline.cli.llm.AskerTest.Answer;
import dev.faultline.cli.llm.AskerTest.ScriptedLlm;
import dev.faultline.cli.llm.Llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The cache the benchmark ships: answers from more than one folder, the same on every machine, needing no key. */
class LlmCacheTest {
    private static List<String> none(Answer a) {
        return List.of();
    }

    @Test
    void anAnswerInAnotherFolderIsServedAndCopiedWhereAsked(@TempDir Path dir) throws Exception {
        Path own = dir.resolve("own");
        Path shipped = dir.resolve("shipped");
        Path used = dir.resolve("used");
        new Asker(new ScriptedLlm("{\"duplicateCheck\":\"no\"}"), new LlmCache(shipped), line -> {})
            .ask("system", "question", AskerTest.SCHEMA, Answer.class, LlmCacheTest::none);

        var llm = new ScriptedLlm();
        var answer = new Asker(llm, new LlmCache(own).alsoReading(shipped).copyingTo(used), line -> {})
            .ask("system", "question", AskerTest.SCHEMA, Answer.class, LlmCacheTest::none);

        assertThat(answer.cached()).isTrue();
        assertThat(answer.value().duplicateCheck()).isEqualTo("no");
        assertThat(llm.calls).isEmpty();
        assertThat(own).doesNotExist();   // the other folder is only read
        try (var files = Files.list(used)) {
            assertThat(files.toList()).hasSize(1);
        }
    }

    @Test
    void aQuestionAskedWithWindowsLineEndingsIsTheSameQuestion(@TempDir Path dir) {
        // an entry cached before Faultline sent \n only: its key is of the \r\n prompt
        String crlf = "code:\r\n  void charge() {\r\n  }\r\n";
        var messages = List.of(Message.system("system"), Message.user(crlf));
        new LlmCache(dir.resolve("old")).put(LlmCache.key("scripted", AskerTest.SCHEMA, messages), "scripted", messages, "{\"duplicateCheck\":\"yes\"}");

        var llm = new ScriptedLlm();
        var cache = new LlmCache(dir.resolve("new")).alsoReading(dir.resolve("old"));
        var answer = new Asker(llm, cache, line -> {}).ask("system", crlf, AskerTest.SCHEMA, Answer.class, LlmCacheTest::none);
        var again = new Asker(llm, new LlmCache(dir.resolve("new")), line -> {}).ask("system", crlf.replace("\r\n", "\n"), AskerTest.SCHEMA,
            Answer.class, LlmCacheTest::none);

        assertThat(answer.value().duplicateCheck()).isEqualTo("yes");
        assertThat(again.cached()).isTrue();   // cached again under the \n key, so the next one finds it by key
        assertThat(llm.calls).isEmpty();
    }

    @Test
    void anAnswerFromTheCacheNeedsNoKeyButAQuestionItLacksSaysWhatIsMissing(@TempDir Path dir) {
        var noKey = OpenAiCompatibleLlm.fromSettings(SettingsAccess.of(Map.of()), "gemini-x", line -> {});
        var messages = List.of(Message.system("system"), Message.user("question"));
        new LlmCache(dir).put(LlmCache.key("gemini-x", AskerTest.SCHEMA, messages), "gemini-x", messages, "{\"duplicateCheck\":\"no\"}");
        var asker = new Asker(noKey, new LlmCache(dir), line -> {});

        assertThat(asker.ask("system", "question", AskerTest.SCHEMA, Answer.class, LlmCacheTest::none).cached()).isTrue();
        assertThatThrownBy(() -> asker.ask("system", "another question", AskerTest.SCHEMA, Answer.class, LlmCacheTest::none))
            .hasMessageStartingWith("FAULTLINE_LLM_API_KEY is not set, and the LLM cache has no answer to this question for gemini-x");
    }
}
