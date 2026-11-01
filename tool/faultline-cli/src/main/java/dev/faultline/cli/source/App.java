package dev.faultline.cli.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.faultline.cli.app.Environment;
import dev.faultline.cli.source.JavaSources.Method;

/**
 * Everything Faultline reads from an app's folder, read once per command, and what faultline doctor learned about it as
 * it runs ({@code environment}, null when doctor has not run or a command judges the files alone). The folder is a repo
 * with one or more {@code modules}, the Spring Boot apps in it, each with its own configuration; {@code code} is all of
 * their code, each method with its module.
 */
public record App(Path dir, RepoFiles files, List<Module> modules, JavaSources code, Environment environment) {
    /** The app's own folder, as environment.json names the module a service builds. */
    public static final String ROOT_MODULE = "";

    /**
     * A Spring Boot app of the repo: its folder from the repo's ({@code path}, "" for the repo itself), named after that
     * folder, with its own files and its configuration as its service runs it.
     */
    public record Module(String path, String name, RepoFiles files, SpringConfig config) {
        Module with(Map<String, String> settings) {
            return new Module(path, name, files, config.with(settings));
        }
    }

    public static App read(Path dir) throws IOException {
        return read(dir, (Environment) null);
    }

    /** The app as its services run it, by the environment doctor recorded: active profiles and environment variables. */
    public static App read(Path dir, Environment environment) throws IOException {
        return read(dir, environment, module -> environment == null ? Map.of() : environment.runtimeOf(module));
    }

    /** The app as a service with these environment variables runs it. */
    public static App read(Path dir, Map<String, String> environment) throws IOException {
        return read(dir, null, module -> environment);
    }

    private static App read(Path dir, Environment environment, Function<String, Map<String, String>> runtime) throws IOException {
        var files = RepoFiles.scan(dir);
        var paths = files.springBootModules();
        // no @SpringBootApplication to go by: the folder is the app
        if (paths.isEmpty()) paths = List.of(ROOT_MODULE);
        var modules = new ArrayList<Module>();
        for (String path : paths) {
            var own = files.within(path);
            modules.add(new Module(path, own.name(), own, SpringConfig.load(own, runtime.apply(path))));
        }
        return new App(dir, files, List.copyOf(modules), JavaSources.parse(files, modules), environment);
    }

    public String name() {
        return files.name();
    }

    /** Whether the repo holds more than one Spring Boot app. */
    public boolean multiModule() {
        return modules.size() > 1;
    }

    /** The configuration of the app's one module. */
    public SpringConfig config() {
        if (multiModule()) {
            throw new IllegalStateException(name() + " has " + modules.size() + " Spring Boot apps, each with its own configuration");
        }
        return modules.getFirst().config();
    }

    public Optional<Module> module(String path) {
        return modules.stream().filter(m -> m.path().equals(path)).findFirst();
    }

    /** The module a method is in; code outside every module (a library of the repo) has none. */
    public Optional<Module> moduleOf(Method m) {
        return m.module() == null ? Optional.empty() : module(m.module());
    }

    /** The configuration a method runs with: its module's. */
    public SpringConfig configOf(Method m) {
        return moduleOf(m).map(Module::config).orElse(multiModule() ? SpringConfig.EMPTY : config());
    }

    public Optional<Environment> runtime() {
        return Optional.ofNullable(environment);
    }

    /**
     * The configurations of these modules for a prompt, by section title: an app of one module has it under {@code title},
     * a repo of several has each under its own, such as "Spring configuration of shipping (shop/shipping/, flattened)".
     */
    public Map<String, String> configSections(String title, Collection<Module> of) {
        var sections = new LinkedHashMap<String, String>();
        if (!multiModule()) {
            sections.put(title, config().asText());
            return sections;
        }
        for (var m : of) sections.put("Spring configuration of %s (%s/, flattened)".formatted(m.name(), m.path()), m.config().asText());
        return sections;
    }

    /**
     * For a prompt: the repo's Spring Boot apps, one line each, with the Compose services that run them when doctor has
     * run. Null for an app of one module.
     */
    public String describeModules() {
        if (!multiModule()) return null;
        return modules.stream().map(m -> {
            var services = environment == null ? Set.<String>of() : environment.servicesIn(m.path());
            return "- %s (%s/)%s".formatted(m.name(), m.path(), services.isEmpty() ? "" : ", run by the Compose service " + String.join(", ", services));
        }).collect(Collectors.joining("\n"));
    }

    /** The tables of the app's databases as the running app made them, for a prompt; null without an environment. */
    public String liveSchema() {
        return environment == null ? null : environment.describeSchemaOf(ROOT_MODULE);
    }

    /** The app as it runs with these settings on top of each module's own configuration. */
    public App withSettings(Map<String, String> settings) {
        if (settings.isEmpty()) return this;
        var changed = modules.stream().map(m -> m.with(settings)).toList();
        return new App(dir, files, changed, code.withModules(changed), environment);
    }
}
