package dev.faultline.cli.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The files of an app that Faultline reads: docs, build files, Compose files, Spring configs, database migrations,
 * OpenAPI specs and main Java sources. Build output and tests are skipped, files come back in a fixed order (so an unchanged app
 * gives the same LLM prompt, and a cache hit), and each is capped so one huge file cannot blow up a prompt.
 */
public final class RepoFiles {
    static final int MAX_CHARS = 20_000;
    private static final Set<String> SKIP_DIRS = Set.of(".git", ".idea", ".vscode", ".mvn", ".gradle", ".faultline",
        "target", "build", "out", "node_modules");
    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");
    private static final Pattern COMPOSE =Pattern.compile("(docker-)?compose(\\.[\\w-]+)?\\.ya?ml");
    private static final Pattern SPRING_CONFIG = Pattern.compile("application\\.(yml|yaml|properties)");
    private static final Pattern SPRING_PROFILE_CONFIG = Pattern.compile("application(-[\\w.-]+)?\\.(yml|yaml|properties)");
    private static final Pattern SPEC_FILE = Pattern.compile("(?i).+\\.(ya?ml|json)");
    private static final Pattern SPEC_START = Pattern.compile("(?m)^[\\s{]*\"?(openapi|swagger)\"?\\s*:");
    static final long MAX_SPEC_CHARS = 2_000_000;
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern SQL_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** A file's text, with its path relative to the app folder and / separators. */
    public record TextFile(String path, String text) {}

    private final Path root;
    private final List<String> paths;

    private RepoFiles(Path root, List<String> paths) {
        this.root = root;
        this.paths = paths;
    }

    public static RepoFiles scan(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("not a folder: " + root);
        var paths = new ArrayList<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean testSources = name.equals("test") && dir.getParent() != null && dir.getParent().endsWith("src");
                return !dir.equals(root) && (SKIP_DIRS.contains(name) || testSources) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                paths.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        paths.sort(null);
        return new RepoFiles(root, List.copyOf(paths));
    }

    public Path root() {
        return root;
    }

    /** The files under a folder of this one ("" is all of them), with paths from that folder. */
    public RepoFiles within(String folder) {
        if (folder.isEmpty()) return this;
        String prefix = folder + "/";
        return new RepoFiles(root.resolve(folder), paths.stream().filter(p -> p.startsWith(prefix)).map(p -> p.substring(prefix.length())).toList());
    }

    /**
     * The Spring Boot apps in this folder, as paths from it ("" for the folder itself): each a folder with a build file
     * whose src/main/java has a class annotated @SpringBootApplication. Empty when there is none.
     */
    public List<String> springBootModules() {
        var modules = new ArrayList<String>();
        for (String p : paths) {
            if (!BUILD_FILES.contains(fileName(p))) continue;
            String folder = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "";
            String sources = (folder.isEmpty() ? "" : folder + "/") + "src/main/java/";
            if (modules.contains(folder)) continue;
            boolean app = paths.stream().filter(j -> j.startsWith(sources) && j.endsWith(".java"))
                .anyMatch(j -> readWhole(j).contains("@SpringBootApplication"));
            if (app) modules.add(folder);
        }
        modules.sort(null);
        return modules;
    }

    /** The app's name: its folder name. */
    public String name() {
        return root.toAbsolutePath().normalize().getFileName().toString();
    }

    /** README.md at the top, and every Markdown file under docs/. */
    public List<TextFile> docs() {
        return read(p -> p.equalsIgnoreCase("README.md") || p.startsWith("docs/") && p.endsWith(".md"));
    }

    public List<TextFile> buildFiles() {
        return read(p -> Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts").contains(fileName(p)));
    }

    private String readWhole(String path) {
        try {
            return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    public List<TextFile> composeFiles() {
        return read(p -> COMPOSE.matcher(fileName(p)).matches());
    }

    /** Flyway or Liquibase SQL and schema.sql, without comments: the schema is what counts, not notes about it. */
    public List<TextFile> migrations() {
        return read(p -> p.endsWith(".sql") && p.contains("src/main/resources/")).stream()
            .map(f -> new TextFile(f.path(), stripSqlComments(f.text())))
            .toList();
    }

    /**
     * OpenAPI and Swagger specs: YAML and JSON files with a top-level openapi or swagger key. Read whole, not cut, since a
     * cut spec would not parse; one over 2 million characters is left out.
     */
    public List<TextFile> openApiSpecs() {
        var specs = new ArrayList<TextFile>();
        for (String p : paths) {
            if (!SPEC_FILE.matcher(fileName(p)).matches() || COMPOSE.matcher(fileName(p)).matches() || SPRING_CONFIG.matcher(fileName(p)).matches()) continue;
            try {
                Path file = root.resolve(p);
                if (Files.size(file) > MAX_SPEC_CHARS) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (SPEC_START.matcher(text.substring(0, Math.min(text.length(), 2_000))).find()) specs.add(new TextFile(p, text));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + p, e);
            }
        }
        return specs;
    }

    public List<TextFile> springConfigs() {
        return read(p -> p.contains("src/main/resources/") && SPRING_CONFIG.matcher(fileName(p)).matches());
    }

    /** The base files and every profile's, such as application-prod.yml. */
    public List<TextFile> allSpringConfigs() {
        return read(p -> p.contains("src/main/resources/") && SPRING_PROFILE_CONFIG.matcher(fileName(p)).matches());
    }

    /** application-{profile}.yml, .yaml or .properties: what a Spring profile sets on top of the base files. */
    public List<TextFile> springProfileConfigs(String profile) {
        var names = Set.of("application-" + profile + ".yml", "application-" + profile + ".yaml", "application-" + profile + ".properties");
        return read(p -> p.contains("src/main/resources/") && names.contains(fileName(p)));
    }

    /**
     * Shell scripts (*.sh, *.bash, mvnw, gradlew) with Windows line endings: a Linux container cannot run them, which
     * is what a Windows checkout with git's core.autocrlf on gives.
     */
    public List<String> scriptsWithWindowsLineEndings() {
        var found = new ArrayList<String>();
        for (String p : paths) {
            String name = fileName(p);
            if (!(name.endsWith(".sh") || name.endsWith(".bash") || name.equals("mvnw") || name.equals("gradlew"))) continue;
            try {
                if (new String(Files.readAllBytes(root.resolve(p)), StandardCharsets.ISO_8859_1).contains("\r\n")) found.add(p);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + p, e);
            }
        }
        return found;
    }

    public List<Path> javaSources() {
        return paths.stream().filter(p -> p.contains("src/main/java/") && p.endsWith(".java")).map(root::resolve).toList();
    }

    public String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private List<TextFile> read(Predicate<String> which) {
        return paths.stream().filter(which).map(this::read).toList();
    }

    private TextFile read(String path) {
        try {
            String text = Files.readString(root.resolve(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "\n... (cut at " + MAX_CHARS + " characters)";
            return new TextFile(path, text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    static String stripSqlComments(String sql) {
        String s = SQL_LINE_COMMENT.matcher(SQL_BLOCK_COMMENT.matcher(sql).replaceAll("")).replaceAll("");
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip() + "\n";
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
