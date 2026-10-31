package dev.faultline.cli.report;

import java.awt.Desktop;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "report", mixinStandardHelpOptions = true,
    description = "Write a run folder's report.html again (faultline run already writes one), and optionally open it.")
public final class ReportCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "RUN_FOLDER", description = "A run folder, such as runs/2026-10-31T10-15-00Z")
    Path runDir;

    @Option(names = "--open", description = "Open the report in your browser")
    boolean open;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        if (!Files.isRegularFile(runDir.resolve("result.json"))) {
            throw new IllegalArgumentException(runDir + " has no result.json: is it a finished run folder?");
        }
        Path report = ReportWriter.write(runDir);
        Out.line(out, "report    %s", Out.path(report));
        if (open) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(report.toAbsolutePath().toUri());
            } else {
                Out.line(out, "          cannot open a browser from here: open the file yourself");
            }
        }
        return Faultline.PASS;
    }
}
