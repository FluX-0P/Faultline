package dev.faultline.cli;

import java.util.TimeZone;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "faultline", mixinStandardHelpOptions = true, version = "faultline 0.1.0",
    description = "Crash-test lab for Spring Boot + Kafka apps.",
    subcommands = RunCommand.class)
public final class Faultline {
    /** Exit codes: every check passed, a check failed, or the tool itself could not run. */
    public static final int PASS = 0;
    public static final int FAIL = 1;
    public static final int ERROR = 2;

    public static void main(String[] args) {
        // Postgres rejects the legacy zone name Java reports on Windows with IST ("Asia/Calcutta")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        int exitCode = new CommandLine(new Faultline())
            .setExecutionExceptionHandler((e, cmd, parsed) -> {
                cmd.getErr().println("error: " + describe(e));
                return ERROR;
            })
            .execute(args);
        System.exit(exitCode);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        return root == e || root.getMessage() == null ? message : message + " (" + root.getMessage() + ")";
    }
}
