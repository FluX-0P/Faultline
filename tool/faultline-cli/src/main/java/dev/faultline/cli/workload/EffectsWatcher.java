package dev.faultline.cli.workload;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks at the effects table every 250ms during a run and remembers when each request ID first showed up there, by
 * Faultline's clock, so recovery-within can tell how soon after a fault the app caught up. It has its own connection.
 */
public final class EffectsWatcher implements AutoCloseable {
    static final Duration EVERY = Duration.ofMillis(250);

    private final Connection db;
    private final EffectsTable effects;
    private final Map<String, Instant> firstSeen = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private volatile Exception failure;
    private Thread thread;

    public EffectsWatcher(Connection db, EffectsTable effects) {
        this.db = db;
        this.effects = effects;
    }

    public void start() {
        thread = Thread.ofVirtual().name("faultline-effects-watcher").start(this::watch);
    }

    private void watch() {
        try {
            while (running) {
                look();
                Thread.sleep(EVERY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            failure = e;
        }
    }

    private void look() throws Exception {
        Instant now = Instant.now();
        for (String id : effects.appliedCounts(db).keySet()) firstSeen.putIfAbsent(id, now);
    }

    /** Stops watching after one last look, and returns when each request ID first appeared. */
    public Map<String, Instant> stop() throws Exception {
        running = false;
        if (thread != null) thread.join();
        if (failure != null) throw new IllegalStateException("watching " + effects.table() + " failed", failure);
        look();
        return Map.copyOf(firstSeen);
    }

    @Override
    public void close() throws Exception {
        running = false;
        if (thread != null) thread.join();
        db.close();
    }
}
