package dev.faultline.cli.fault;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import dev.faultline.cli.app.DockerStack;
import dev.faultline.cli.network.Toxiproxy;
import dev.faultline.cli.plan.Durations;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.stub.StubControl;

/**
 * A fault that lasts a while, fired from a background thread {@code at} a time after the traffic starts: it begins,
 * lasts {@code duration}, then ends, and repeats {@code times} times, 10s apart. A process fault kills, pauses or
 * restarts a Docker service; a network fault slows, stalls or cuts a Toxiproxy proxy; a service outage takes the stub
 * down. Remembers when the last one ended, for recovery-within.
 */
public final class TimedFaults implements AutoCloseable {
    static final Duration GAP = Duration.ofSeconds(10);
    private static final String TOXIC = "faultline";

    private interface Step {
        void run() throws Exception;
    }

    /** One fault: what to print when it begins, and how to begin and end it. */
    private record Window(String says, Step begin, Step end) {}

    private final Plan.Fault fault;
    private final Window window;
    private final Path runDir;
    private final Consumer<String> print;
    private Thread thread;
    private volatile boolean active;
    private volatile Instant lastEnded;
    private volatile Exception failure;

    public TimedFaults(Plan.Fault fault, DockerStack stack, Toxiproxy toxiproxy, StubControl stub, Path runDir, Consumer<String> print) {
        this.fault = fault;
        this.window = window(fault, stack, toxiproxy, stub);
        this.runDir = runDir;
        this.print = print;
    }

    private static Window window(Plan.Fault f, DockerStack stack, Toxiproxy toxiproxy, StubControl stub) {
        String t = f.target();
        long s = f.duration() == null ? 0 : Durations.parse(f.duration()).toSeconds();
        return switch (f.layer() + " " + f.action()) {
            case "process kill" -> new Window("killed %s, starting it again in %ds".formatted(t, s), () -> stack.kill(t), () -> stack.start(t));
            case "process pause" -> new Window("froze %s for %ds".formatted(t, s), () -> stack.pause(t), () -> stack.unpause(t));
            case "process restart" -> new Window("restarting " + t, () -> stack.restart(t), () -> { });
            case "network latency" -> new Window("every reply through %s %s late, for %ds".formatted(t, f.latency(), s),
                () -> toxiproxy.addLatency(t, TOXIC, Durations.parse(f.latency())), () -> toxiproxy.removeToxic(t, TOXIC));
            case "network timeout" -> new Window("no replies through %s for %ds".formatted(t, s),
                () -> toxiproxy.addTimeout(t, TOXIC), () -> toxiproxy.removeToxic(t, TOXIC));
            case "network cut" -> new Window("cut every connection through %s, for %ds".formatted(t, s),
                () -> toxiproxy.enable(t, false), () -> toxiproxy.enable(t, true));
            case "service outage" -> new Window("the stub is down for %ds".formatted(s), () -> stub.outage(true), () -> stub.outage(false));
            default -> throw new IllegalArgumentException("not a fault that lasts a while: " + f.layer() + " " + f.action());
        };
    }

    /** Starts the clock: call it when the traffic starts. */
    public void start() {
        thread = Thread.ofVirtual().name("faultline-timed-faults").start(this::fireAll);
    }

    /** Waits until every planned fault has fired and ended; throws if one could not be started or ended. */
    public void awaitDone() throws Exception {
        if (thread != null) thread.join();
        if (failure != null) throw failure;
    }

    /** When the last fault ended, or null if none has. */
    public Instant lastEnded() {
        return lastEnded;
    }

    private void fireAll() {
        Duration at = Durations.parse(fault.at());
        Duration lasts = fault.duration() == null ? Duration.ZERO : Durations.parse(fault.duration());
        long start = System.nanoTime();
        try {
            for (int i = 0; i < fault.count(); i++) {
                long due = start + at.toNanos() + i * (lasts.toNanos() + GAP.toNanos());
                Thread.sleep(Duration.ofNanos(Math.max(0, due - System.nanoTime())));
                Faults.logFired(runDir, fault.layer(), fault.action(), fault.target());
                print.accept("fault     " + window.says());
                active = true;
                window.begin().run();
                Thread.sleep(lasts);
                window.end().run();
                active = false;
                lastEnded = Instant.now();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            failure = e;
            print.accept("fault     " + fault.action() + " failed: " + e.getMessage());
        }
    }

    /** Stops early if the run is aborted, and never leaves a fault in place. */
    @Override
    public void close() throws Exception {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            thread.join();
        }
        if (active) window.end().run();
    }
}
