package dev.faultline.cli.app;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs the target app as a child process and restarts it after a planned crash, the way an orchestrator
 * would. All its output, across every restart, goes to one app.log.
 */
public final class AppProcess implements AutoCloseable {
    /** The agent's crash exit code (like kill -9). Any other exit is a real failure and ends the run. */
    public static final int CRASH_EXIT_CODE = 137;
    static final Duration RESTART_DELAY = Duration.ofSeconds(2);

    private final List<String> command;
    private final Map<String, String> env;
    private final Path log;
    private final Consumer<String> print;
    private final Thread shutdownHook = new Thread(this::stop, "faultline-app-stop");

    private Process process;      // guarded by this
    private boolean stopping;     // guarded by this
    private int restarts;         // guarded by this
    private volatile String failure;

    public AppProcess(List<String> command, Map<String, String> env, Path log, Consumer<String> print) {
        this.command = List.copyOf(command);
        this.env = Map.copyOf(env);
        this.log = log;
        this.print = print;
    }

    /** {@code java [-javaagent:agent.jar] -jar app.jar [args]}, with the JVM Faultline itself runs on. */
    public static List<String> command(Path appJar, Path agentJar, List<String> appArgs) {
        var cmd = new ArrayList<String>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // the same Windows/IST workaround as the CLI: Postgres rejects the "Asia/Calcutta" zone name
        cmd.add("-Duser.timezone=UTC");
        if (agentJar != null) cmd.add("-javaagent:" + agentJar.toAbsolutePath());
        cmd.add("-jar");
        cmd.add(appJar.toAbsolutePath().toString());
        cmd.addAll(appArgs);
        return cmd;
    }

    public void start() throws IOException {
        Runtime.getRuntime().addShutdownHook(shutdownHook); // Ctrl+C must not leave the app running
        launch();
        Thread.ofPlatform().daemon().name("faultline-app-watcher").start(this::watch);
    }

    private synchronized void launch() throws IOException {
        var builder = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().putAll(env);
        process = builder.start();
    }

    private void watch() {
        try {
            while (true) {
                Process current;
                synchronized (this) {
                    current = process;
                }
                int code = current.waitFor();
                synchronized (this) {
                    if (stopping) return;
                }
                if (code != CRASH_EXIT_CODE) {
                    failure = "the app exited with code " + code + " (see " + log + ")";
                    return;
                }
                print.accept("app       crashed as planned (exit %d), restarting in %ds".formatted(code, RESTART_DELAY.toSeconds()));
                Thread.sleep(RESTART_DELAY);
                synchronized (this) {
                    if (stopping) return;
                    restarts++;
                    launch();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            failure = "could not restart the app: " + e.getMessage();
        }
    }

    /** Throws if the app died for any reason other than a planned crash. */
    public void checkHealthy() {
        String f = failure;
        if (f != null) throw new IllegalStateException(f);
    }

    public synchronized int restarts() {
        return restarts;
    }

    public synchronized long pid() {
        return process == null ? -1 : process.pid();
    }

    @Override
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // the hook is running or about to; stop() below is safe to call twice
        }
        stop();
    }

    private void stop() {
        Process current;
        synchronized (this) {
            stopping = true;
            current = process;
        }
        if (current == null || !current.isAlive()) return;
        current.destroy();
        try {
            if (!current.waitFor(10, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException e) {
            current.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
