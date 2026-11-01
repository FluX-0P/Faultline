package dev.faultline.cli.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import dev.faultline.cli.app.Environment;

/**
 * Everything Faultline reads from an app's folder, read once per command, and what faultline doctor learned about it as
 * it runs ({@code environment}, null when doctor has not run or a command judges the files alone).
 */
public record App(Path dir, RepoFiles files, SpringConfig config, JavaSources code, Environment environment) {
    /** The app's own folder, as environment.json names the module a service builds. */
    public static final String ROOT_MODULE = "";

    public static App read(Path dir) throws IOException {
        return read(dir, (Environment) null);
    }

    /** The app as its service runs it, by the environment doctor recorded: active profiles and environment variables. */
    public static App read(Path dir, Environment environment) throws IOException {
        var files = RepoFiles.scan(dir);
        var config = SpringConfig.load(files, environment == null ? Map.of() : environment.runtimeOf(ROOT_MODULE));
        return new App(dir, files, config, JavaSources.parse(files, config), environment);
    }

    /** The app as a service with these environment variables runs it. */
    public static App read(Path dir, Map<String, String> environment) throws IOException {
        var files = RepoFiles.scan(dir);
        var config = SpringConfig.load(files, environment);
        return new App(dir, files, config, JavaSources.parse(files, config), null);
    }

    public String name() {
        return files.name();
    }

    public Optional<Environment> runtime() {
        return Optional.ofNullable(environment);
    }

    /** The tables of the app's databases as the running app made them, for a prompt; null without an environment. */
    public String liveSchema() {
        return environment == null ? null : environment.describeSchemaOf(ROOT_MODULE);
    }

    /** The app as it runs with these settings on top of its own configuration. */
    public App withSettings(Map<String, String> settings) {
        if (settings.isEmpty()) return this;
        var changed = config.with(settings);
        return new App(dir, files, changed, code.withConfig(changed), environment);
    }
}
