package dev.faultline.cli.scan;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.llm.Asker;
import dev.faultline.cli.llm.Prompt;
import dev.faultline.cli.llm.Schema;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.JavaSources.Method;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "scan", mixinStandardHelpOptions = true,
    description = {"Find risky code and ask the LLM to judge each candidate: .faultline/<app>/risks.json",
        "Pattern P1 for now: a Kafka listener that saves, then acknowledges the message."})
public final class ScanCommand implements Callable<Integer> {
    static final String P1 = "P1";
    static final String P1_TITLE = "Save, then acknowledge the message";
    static final String SYSTEM = """
        You review Kafka consumer code for Faultline, a tool that crash-tests apps to find lost or duplicated data.

        The listener below does its work (usually database writes, committed in a transaction) and then acknowledges
        the Kafka message. If the process crashes after the work is committed but before the acknowledgment, Kafka
        delivers the same message again after the restart, and the work runs a second time.

        Question: does the code protect against that second run with a duplicate check that commits in the same
        transaction as the work? Checks that count: recording the message's ID under a unique key in the same
        transaction and skipping IDs already recorded; a unique constraint that stops the second run before any of its
        effects happen; an upsert keyed by the message's ID.

        Rules:
        - Judge the code as it runs with the configuration shown. A check that is switched off by default does not count.
        - A check outside the transaction, or one that runs after some effects were already applied, does not count.
        - Comments were removed. Judge only the code, configuration and schema.
        - Answer duplicateCheck "yes" only if such a check protects every effect of the work.
        """;
    private static final List<String> CONFIDENCE_ORDER = List.of("high", "medium", "low");

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "APP_FOLDER", description = "The app's folder, for example demo-app")
    Path appDir;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        App app = App.read(appDir);
        List<Method> candidates = app.code().kafkaListenersThatAcknowledge();
        Out.line(out, "scan      %d P1 candidate(s): Kafka listeners that take an Acknowledgment and call acknowledge()", candidates.size());

        var risky = new ArrayList<Risk>();
        var dismissed = new ArrayList<Risk>();
        String model = null;
        if (!candidates.isEmpty()) {
            var asker = Asker.fromSettings(message -> Out.line(out, "          %s", message));
            model = asker.model();
            for (Method candidate : candidates) {
                Out.line(out, "review    %s (%s:%d), asking %s ...", candidate.handler(), candidate.file(), candidate.line(), model);
                var answer = asker.ask(SYSTEM, prompt(app, candidate), Schema.load("p1-review"), P1Review.class, P1Review::problems);
                P1Review review = answer.value();
                Out.line(out, "          %s%s, %s confidence", answer.cached() ? "(cached) " : "",
                    review.risky() ? "RISKY: no duplicate check in the same transaction" : "safe: has a duplicate check", review.confidence());
                Out.line(out, "          %s", review.why());
                var risk = new Risk(null, P1, P1_TITLE, candidate.handler(), candidate.file(), candidate.line(), review.confidence(), review.why());
                (review.risky() ? risky : dismissed).add(risk);
            }
        }

        risky.sort(Comparator.comparingInt(r -> CONFIDENCE_ORDER.indexOf(r.confidence())));
        var numbered = new ArrayList<Risk>();
        for (var r : risky) {
            numbered.add(new Risk("R" + (numbered.size() + 1), r.pattern(), r.title(), r.location(), r.file(), r.line(), r.confidence(), r.why()));
        }

        Path dir = Workspace.forApp(appDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(Workspace.RISKS);
        Json.writePretty(file, new Risks(app.name(), model, numbered, dismissed));
        Out.line(out, "risks     %d flagged%s", numbered.size(),
            numbered.isEmpty() ? "" : ": " + numbered.stream().map(r -> r.id() + " " + r.pattern() + " " + r.location()).collect(Collectors.joining(", ")));
        Out.line(out, "wrote     %s", file);
        if (!numbered.isEmpty()) Out.line(out, "next      ./faultline plan %s", appDir);
        return Faultline.PASS;
    }

    static String prompt(App app, Method listener) {
        var called = app.code().calledTypes(listener).stream().map(app.code()::source).collect(Collectors.joining("\n\n"));
        return new Prompt()
            .line("Listener: %s (%s, line %d)".formatted(listener.handler(), listener.file(), listener.line()))
            .code("Listener class", "java", app.code().source(listener.owner()))
            .code("Classes it calls", "java", called)
            .code("Spring configuration (flattened)", "properties", app.config().asText())
            .files("Database schema (migrations)", app.files().migrations(), "sql")
            .toString();
    }
}
