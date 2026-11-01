package dev.faultline.cli.llm;

import java.util.List;
import java.util.Map;

import dev.faultline.cli.source.RepoFiles.TextFile;

/** A prompt made of Markdown sections, with code in fenced blocks. Empty sections are left out. */
public final class Prompt {
    private final StringBuilder text = new StringBuilder();

    public Prompt line(String line) {
        text.append(line).append("\n\n");
        return this;
    }

    public Prompt section(String title, String body) {
        if (body == null || body.isBlank()) return this;
        text.append("## ").append(title).append("\n\n").append(body.strip()).append("\n\n");
        return this;
    }

    public Prompt code(String title, String language, String code) {
        if (code == null || code.isBlank()) return this;
        return section(title, fence(language, code));
    }

    /** A code section for each title, in order. */
    public Prompt codes(Map<String, String> byTitle, String language) {
        byTitle.forEach((title, code) -> code(title, language, code));
        return this;
    }

    public Prompt files(String title, List<TextFile> files, String language) {
        if (files.isEmpty()) return this;
        var body = new StringBuilder();
        for (var f : files) body.append("### ").append(f.path()).append("\n\n").append(fence(language, f.text())).append("\n\n");
        return section(title, body.toString());
    }

    private static String fence(String language, String code) {
        return "```" + language + "\n" + code.strip() + "\n```";
    }

    @Override
    public String toString() {
        return text.toString().strip() + "\n";
    }
}
