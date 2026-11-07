package dev.faultline.cli.run;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.trace.Traces;
import dev.faultline.cli.plan.Plan;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "run", mixinStandardHelpOptions = true,
    description = {"Run an approved plan: start the app (with the fault agent when the plan has a code fault), send the plan's",
        "traffic while the fault fires, wait for the app to finish, and check every request's effect. The plan's confirm",
        "settings say how often: runs with the fault, then baselines without it. Same plan and seed, same requests."})
public final class RunCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "PLAN", description = "The plan to run, such as .faultline/demo-app/plan.yaml")
    Path planFile;

    @Option(names = "--seed", description = "Seed for the generated requests. Same seed, same requests (default: the plan's, else random)")
    Long seed;

    @Option(names = "--count", description = "Requests to send (default: the plan's)")
    Integer count;

    @Option(names = "--rate", description = "Requests per second (default: the plan's)")
    Integer rate;

    @Option(names = "--runs", description = "Runs with the fault (default: the plan's confirm.runs, else 1)")
    Integer runs;

    @Option(names = "--baselines", description = "Runs without the fault (default: the plan's confirm.baselines, else 0)")
    Integer baselines;

    @Option(names = "--app-arg", description = "A setting for the app, repeatable, winning over the plan's. Use the = form: --app-arg=--payments.dedupe=claim-first")
    List<String> appArgs = new ArrayList<>();

    @Option(names = "--runs-dir", defaultValue = "runs", description = "Where run folders are written (default: ${DEFAULT-VALUE})")
    Path runsDir;

    @Option(names = "--traces", description = "Keep traces of every request (OpenTelemetry agent in each service, Grafana at http://localhost:3300): the report links each request it flags to its trace")
    boolean traces;

    @Option(names = "--agent-jar", description = "The fault agent jar (default: tool/faultline-agent/target/faultline-agent.jar, next to this CLI)")
    Path agentJar;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        Plan original = Plan.read(planFile);
        long seed = this.seed != null ? this.seed
            : original.traffic() != null && original.traffic().seed() != null ? original.traffic().seed()
            : ThreadLocalRandom.current().nextInt(1, 1_000_000);
        Plan plan = original.forRun(seed, count, rate, appArgs, runs, baselines);
        var problems = plan.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("the plan has problems, fix them in " + Out.path(planFile) + ":\n  - " + String.join("\n  - ", problems));
        }
        if (traces && plan.target().compose() == null) throw new IllegalArgumentException("--traces needs a plan that runs the app with Docker Compose");
        var keep = traces ? Traces.start(Workspace.TOOLS, line -> Out.line(out, "%s", line)) : null;
        var outcome = PlanRun.run(plan, Out.path(planFile), runsDir, agentJar != null ? agentJar : PlanRun.defaultAgentJar(), keep, out);
        return outcome.result().verdict().equals("PASS") ? Faultline.PASS : Faultline.FAIL;
    }
}
