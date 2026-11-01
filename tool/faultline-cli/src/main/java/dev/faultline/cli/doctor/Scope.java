package dev.faultline.cli.doctor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.faultline.cli.source.App;
import dev.faultline.cli.source.RepoFiles;

/**
 * Whether Faultline can test a repo, read from its files before anything boots: a complete checkout whose scripts a
 * Linux container can run (Windows clones can miss both), Spring Boot on
 * Java 17 or newer (the fault agent's minimum), consumers that are {@code @KafkaListener} methods, and a Postgres
 * database. Each reason a repo is out names what it uses instead, so the score stays honest about what Faultline covers.
 */
final class Scope {
    private static final List<Pattern> JAVA_VERSIONS = List.of(
        Pattern.compile("<java\\.version>\\s*([\\d.]+)\\s*<"),
        Pattern.compile("<maven\\.compiler\\.(?:release|source|target)>\\s*([\\d.]+)\\s*<"),
        Pattern.compile("<release>\\s*([\\d.]+)\\s*</release>"),
        Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)"),
        Pattern.compile("(?:source|target)Compatibility\\s*=\\s*(?:JavaVersion\\.VERSION_|['\"])?([\\d._]+)"));
    private static final List<Pattern> BOOT_VERSIONS = List.of(
        Pattern.compile("(?s)<artifactId>spring-boot-(?:starter-parent|dependencies)</artifactId>\\s*<version>\\s*(\\d+)\\."),
        Pattern.compile("org\\.springframework\\.boot['\"]\\)?\\s*version\\s*['\"](\\d+)\\."),
        Pattern.compile("spring-boot-gradle-plugin:(\\d+)\\."));
    static final int MIN_JAVA = 17;
    /** A clone that is complete and runs in Linux containers when made on Windows. */
    static final String CLONE = "git -c core.longpaths=true -c core.autocrlf=false clone URL";

    /**
     * @param out why Faultline cannot test the repo; empty when it can
     * @param found what it uses, in one line, such as "Spring Boot 3 on Java 17, 3 @KafkaListener methods, Postgres"
     * @param notes what Faultline could not tell, which does not keep the repo out
     */
    record Verdict(List<String> out, String found, List<String> notes) {}

    private Scope() {}

    static Verdict check(Path repo, App app) {
        var out = new ArrayList<String>();
        var notes = new ArrayList<String>();
        var found = new ArrayList<String>();
        missing(repo, out, notes);
        var crlf = app.files().scriptsWithWindowsLineEndings();
        if (!crlf.isEmpty()) {
            out.add("%d shell script%s of the checkout %s Windows line endings, such as %s, which a Linux container cannot run: clone again with %s"
                .formatted(crlf.size(), crlf.size() == 1 ? "" : "s", crlf.size() == 1 ? "has" : "have", crlf.getFirst(), CLONE));
        }

        String builds = app.files().buildFiles().stream().map(RepoFiles.TextFile::text).collect(Collectors.joining("\n"));
        if (!builds.contains("spring-boot")) {
            out.add("not a Spring Boot app: no build file uses Spring Boot");
        } else {
            OptionalInt boot = lowest(BOOT_VERSIONS, builds);
            OptionalInt java = lowest(JAVA_VERSIONS, builds);
            found.add("Spring Boot" + (boot.isPresent() ? " " + boot.getAsInt() : "") + (java.isPresent() ? " on Java " + java.getAsInt() : ""));
            if (java.isPresent() && java.getAsInt() < MIN_JAVA) {
                out.add("Java " + java.getAsInt() + ": Faultline's fault agent needs Java " + MIN_JAVA + " or newer");
            } else if (java.isEmpty() && (boot.isEmpty() || boot.getAsInt() < 3)) {
                notes.add("cannot tell the Java version from the build files: Faultline's fault agent needs Java " + MIN_JAVA + " or newer");
            }
        }

        int listeners = app.code().kafkaListeners().size();
        if (listeners > 0) {
            found.add(listeners + " @KafkaListener method" + (listeners == 1 ? "" : "s"));
        } else if (builds.contains("spring-cloud-stream") || builds.contains("spring-cloud-starter-stream")) {
            out.add("its consumers are Spring Cloud Stream functions, not @KafkaListener methods: Faultline does not cover Spring Cloud Stream yet");
        } else if (builds.contains("spring-boot-starter-amqp") || builds.contains("spring-rabbit")) {
            out.add("it consumes from RabbitMQ: Faultline tests apps that consume Kafka messages");
        } else {
            out.add("no @KafkaListener method: Faultline tests apps that consume Kafka messages");
        }

        String configs = Stream.concat(app.files().allSpringConfigs().stream(), app.files().composeFiles().stream())
            .map(RepoFiles.TextFile::text).collect(Collectors.joining("\n")).toLowerCase();
        String deps = builds.toLowerCase();
        if (configs.contains("jdbc:postgresql") || configs.contains("r2dbc:postgresql") || deps.contains("org.postgresql") || deps.contains("r2dbc-postgresql")) {
            found.add("Postgres");
        } else if (configs.contains("jdbc:h2") || deps.contains("com.h2database")) {
            out.add("H2 only: an in-memory database inside the app, which Faultline cannot check from outside");
        } else if (configs.contains("jdbc:mysql") || configs.contains("jdbc:mariadb") || deps.contains("mysql-connector") || deps.contains("mariadb-java-client")) {
            out.add("MySQL: Faultline checks Postgres databases only, for now");
        } else if (deps.contains("mongodb")) {
            out.add("MongoDB: Faultline checks Postgres databases only, for now");
        } else {
            out.add("no Postgres database: Faultline checks what the app writes to Postgres");
        }
        return new Verdict(out, String.join(", ", found), notes);
    }

    /** Files git knows but the checkout lacks: on Windows, a clone silently leaves out files whose paths are too long. */
    private static void missing(Path repo, List<String> out, List<String> notes) {
        try {
            Process p = new ProcessBuilder("git", "-c", "core.longpaths=true", "-C", repo.toString(), "ls-files", "--deleted")
                .redirectErrorStream(false).start();
            p.getOutputStream().close();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(1, TimeUnit.MINUTES) || p.exitValue() != 0) {
                notes.add("not a git checkout: cannot tell whether every file is there");
                return;
            }
            var missing = text.lines().filter(l -> !l.isBlank()).toList();
            if (!missing.isEmpty()) {
                out.add("%d file%s of the checkout %s missing, such as %s: on Windows a clone leaves out paths that are too long, so clone again with %s"
                    .formatted(missing.size(), missing.size() == 1 ? "" : "s", missing.size() == 1 ? "is" : "are", missing.getFirst(), CLONE));
            }
        } catch (Exception e) {
            notes.add("cannot run git (" + e.getMessage() + "): cannot tell whether every file is there");
        }
    }

    /** The lowest version any of the patterns finds, with 1.8 read as 8. */
    static OptionalInt lowest(List<Pattern> patterns, String text) {
        return patterns.stream().flatMap(p -> p.matcher(text).results().map(m -> m.group(1)))
            .mapToInt(Scope::major).filter(v -> v > 0).min();
    }

    private static int major(String version) {
        String[] parts = version.replace('_', '.').split("\\.");
        try {
            int first = Integer.parseInt(parts[0]);
            return first == 1 && parts.length > 1 ? Integer.parseInt(parts[1]) : first;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
