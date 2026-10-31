package dev.faultline.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    @Test
    void readsDotEnvLines() {
        var values = Settings.parse(List.of(
            "# a comment",
            "",
            "FAULTLINE_LLM_API_KEY=abc123",
            "export FAULTLINE_LLM_MODEL = \"gemini-3.5-flash-lite\"",
            "FAULTLINE_LLM_BASE_URL='https://example.test/v1/'",
            "PLAIN=value # trailing comment",
            "not a setting"));

        assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.of(
            "FAULTLINE_LLM_API_KEY", "abc123",
            "FAULTLINE_LLM_MODEL", "gemini-3.5-flash-lite",
            "FAULTLINE_LLM_BASE_URL", "https://example.test/v1/",
            "PLAIN", "value"));
    }

    @Test
    void realEnvironmentVariablesWinOverTheFile(@TempDir Path dir) throws Exception {
        Path dotEnv = dir.resolve(".env");
        Files.writeString(dotEnv, "A=from-file\nB=from-file\nC=\n");

        var settings = Settings.load(dotEnv, Map.of("A", "from-env", "B", " "));

        assertThat(settings.get("A")).contains("from-env");
        assertThat(settings.get("B")).contains("from-file");   // a blank variable does not hide the file's value
        assertThat(settings.get("C")).isEmpty();
        assertThatThrownBy(() -> settings.require("C", "set it in .env")).hasMessage("C is not set: set it in .env");
    }

    @Test
    void noDotEnvIsFine(@TempDir Path dir) {
        assertThat(Settings.load(dir.resolve(".env"), Map.of()).get("ANY")).isEmpty();
    }
}
