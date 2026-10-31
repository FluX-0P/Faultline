package dev.faultline.cli.fault;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

import dev.faultline.cli.app.DockerStack;
import dev.faultline.cli.plan.Durations;
import dev.faultline.cli.plan.Plan;

/**
 * A process fault on one Docker service, fired from a background thread {@code at} a time after the traffic starts:
 * kill it and start it again after {@code duration}, pause (freeze) it for {@code duration}, or restart it.
 * Repeats {@code times} times, 10s apart.
 */
public final class ProcessFaults implements AutoCloseable {
    static final Duration GAP = Duration.ofSeconds(10);

    private final Plan.Fault fault;
    private final DockerStack stack;
    private final Path runDir;
    private final Consumer<String> print;
    private Thread thread;
    private volatile boolean paused;
    private volatile Exception failure;

    public ProcessFaults(Plan.Fault fault, DockerStack stack, Path runDir, Consumer<String> print) {
        this.fault = fault;
        this.stack = stack;
        this.runDir = runDir;
        this.print = print;
    }

    /** Starts the clock: call it when the traffic starts. */
    public void start() {
        thread = Thread.ofVirtual().name("faultline-process-faults").start(this::fireAll);
    }

    /** Waits until every planned fault has fired and the service is back; throws if a docker call failed. */
    public void awaitDone() throws Exception {
        if (thread != null) thread.join();
        if (failure != null) throw failure;
    }

    private void fireAll() {
        Duration at = Durations.parse(fault.at());
        Duration down = fault.duration() == null ? Duration.ZERO : Durations.parse(fault.duration());
        long start = System.nanoTime();
        try {
            for (int i = 0; i < fault.count(); i++) {
                long due = start + at.toNanos() + i * (down.toNanos() + GAP.toNanos());
                Thread.sleep(Duration.ofNanos(Math.max(0, due - System.nanoTime())));
                fire(down);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            failure = e;
            print.accept("fault     " + fault.action() + " " + fault.target() + " failed: " + e.getMessage());
        }
    }

    private void fire(Duration down) throws Exception {
        String target = fault.target();
        Faults.logFired(runDir, "process", fault.action(), target);
        switch (fault.action()) {
            case "kill" -> {
                print.accept("fault     killed %s, starting it again in %ds".formatted(target, down.toSeconds()));
                stack.kill(target);
                Thread.sleep(down);
                stack.start(target);
            }
            case "pause" -> {
                print.accept("fault     froze %s for %ds".formatted(target, down.toSeconds()));
                paused = true;
                stack.pause(target);
                Thread.sleep(down);
                stack.unpause(target);
                paused = false;
            }
            case "restart" -> {
                print.accept("fault     restarting " + target);
                stack.restart(target);
            }
            default -> throw new IllegalArgumentException("unknown process fault " + fault.action());
        }
    }

    /** Stops early if the run is aborted, and never leaves a service frozen. */
    @Override
    public void close() throws Exception {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            thread.join();
        }
        if (paused) stack.unpause(fault.target());
    }
}
