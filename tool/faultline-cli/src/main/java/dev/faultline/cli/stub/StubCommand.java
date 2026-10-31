package dev.faultline.cli.stub;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import dev.faultline.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "stub", mixinStandardHelpOptions = true,
    description = {"Run the fake payment provider an app under test charges payments at. It keeps a ledger in Postgres",
        "(table stub.charges), honours an Idempotency-Key header, and breaks when faultline run tells it to.",
        "deploy/docker-compose.yml runs it as the provider service. Runs until stopped."})
public final class StubCommand implements Callable<Integer> {
    private static final int CONNECT_TRIES = 30;

    @Spec
    CommandSpec spec;

    @Option(names = "--port", defaultValue = "9090", description = "Port for charges and control (default: ${DEFAULT-VALUE})")
    int port;

    @Option(names = "--db", required = true, description = "JDBC URL of the Postgres database to keep the ledger in")
    String db;

    @Option(names = "--db-user", defaultValue = "demo", description = "(default: ${DEFAULT-VALUE})")
    String dbUser;

    @Option(names = "--db-password", defaultValue = "demo", description = "(default: ${DEFAULT-VALUE})")
    String dbPassword;

    @Option(names = "--recover-below", defaultValue = "50",
        description = "After an outage, stay down while getting more than this many requests a second (default: ${DEFAULT-VALUE})")
    int recoverBelow;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        try (var _ = startWhenTheDatabaseAnswers(out)) {
            Out.line(out, "stub      fake payment provider on port %d, ledger in %s", port, Ledger.TABLE);
            new CountDownLatch(1).await();
        }
        return 0;
    }

    private FakeProvider startWhenTheDatabaseAnswers(PrintWriter out) throws Exception {
        for (int i = 1; ; i++) {
            try {
                return FakeProvider.start(port, db, dbUser, dbPassword, recoverBelow, line -> Out.line(out, "%s", line));
            } catch (SQLException e) {
                if (i == CONNECT_TRIES) throw e;
                Out.line(out, "stub      waiting for the database (%s)", e.getMessage());
                Thread.sleep(Duration.ofSeconds(1));
            }
        }
    }
}
