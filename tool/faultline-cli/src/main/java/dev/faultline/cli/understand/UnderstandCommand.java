package dev.faultline.cli.understand;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.app.Environment;
import dev.faultline.cli.llm.Asker;
import dev.faultline.cli.llm.Prompt;
import dev.faultline.cli.llm.Schema;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.Endpoint;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "understand", mixinStandardHelpOptions = true,
    description = "Read an app's docs, configs, migrations and code signatures, and ask the LLM to map it: .faultline/<app>/app-map.json")
public final class UnderstandCommand implements Callable<Integer> {
    static final String SYSTEM = """
        You map Spring Boot applications for Faultline, a tool that crash-tests apps to find lost or duplicated data.
        From the code outline, endpoints, configuration, database migrations, build files and docs you are given,
        describe the app as JSON that follows the schema.

        Rules:
        - Use only what the input shows. Never invent endpoints, topics, tables, handlers or outside services.
        - Write every handler as Class#method, exactly as it appears in the code outline.
        - entryPoints: exactly one for each endpoint under "Endpoints found in the code", with the same handler.
        - dataStores: the databases and brokers the app uses, with the tables or topics it uses in each.
        - outsideCalls: calls the app makes to services outside itself. Empty if there are none.
        - journeys: what a user or another system does with the app, end to end, named in kebab-case (such as
          place-order). List the steps in order, each with the handler that runs it, including asynchronous steps such
          as Kafka listeners and the service methods they call.
        """;

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "APP_FOLDER", description = "The app's folder, for example demo-app")
    Path appDir;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        App app = App.read(appDir, Environment.read(Workspace.forApp(appDir)).orElse(null));
        if (app.environment() != null) Out.line(out, "doctor    judging %s as it runs, from %s", app.name(), Environment.FILE);
        for (String problem : app.code().problems()) Out.line(out, "warning   skipped %s", problem);
        List<Endpoint> endpoints = app.code().endpoints();
        Out.line(out, "read      %d Java files, %d endpoints, %d migrations, %d docs, %d build and compose files",
            app.code().fileCount(), endpoints.size(), app.files().migrations().size(), app.files().docs().size(),
            app.files().buildFiles().size() + app.files().composeFiles().size());

        var asker = Asker.fromSettings(message -> Out.line(out, "          %s", message));
        Out.line(out, "llm       asking %s to map the app ...", asker.model());
        var answer = asker.ask(SYSTEM, prompt(app, endpoints), Schema.load("app-map"), AppMap.class,
            map -> map.problems(endpoints, app.code().handlers()));
        Out.line(out, answer.cached() ? "          cached answer, no call made" : "          answered in %.1fs".formatted(answer.took().toMillis() / 1000.0));

        AppMap map = answer.value().withSource(app.name(), answer.model());
        Path dir = Workspace.forApp(appDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(Workspace.APP_MAP);
        Json.writePretty(file, map);

        Out.line(out, "map       %s", map.summary());
        for (var e : map.entryPoints()) Out.line(out, "          entry    %-28s %s", e.name(), e.handler());
        for (var j : map.journeys()) {
            Out.line(out, "          journey  %-28s %s", j.name(), j.steps().stream().map(AppMap.Step::handler).collect(Collectors.joining(" -> ")));
        }
        for (var d : map.dataStores()) Out.line(out, "          store    %-28s %s", d.kind() + " " + d.name(), String.join(", ", d.tables()));
        Out.line(out, "wrote     %s", file);
        Out.line(out, "next      ./faultline scan %s", appDir);
        return Faultline.PASS;
    }

    static String prompt(App app, List<Endpoint> endpoints) {
        String endpointList = endpoints.stream()
            .map(e -> "- " + e.name() + (e.groupId() != null ? " (group " + e.groupId() + ")" : "") + " -> " + e.handler())
            .collect(Collectors.joining("\n"));
        return new Prompt()
            .line("App folder: " + app.name())
            .code("Code outline: every type, field, constructor and method, with annotations", "text", app.code().outline())
            .section("Endpoints found in the code", endpointList)
            .code("Spring configuration (application.yml, flattened)", "properties", app.config().asText())
            .files("Database migrations", app.files().migrations(), "sql")
            .code(Environment.SCHEMA_TITLE, "sql", app.liveSchema())
            .files("Build files", app.files().buildFiles(), "xml")
            .files("Compose files", app.files().composeFiles(), "yaml")
            .files("Docs", app.files().docs(), "markdown")
            .toString();
    }
}
