package dev.faultline.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/**
 * What the agent should do. The CLI writes it as a small properties file and passes its path in
 * {@code FAULTLINE_PLAN}; plain properties keep the agent free of any parsing library.
 *
 * <pre>
 * point=PaymentService#apply     (or before-ack: just before Spring Kafka commits the offset)
 * when=before                    (or after: once the method has returned)
 * action=delay                   (crash, delay, throw or barrier; signal for a barrier's partners)
 * delay_ms=15000                 (for delay; for barrier, the longest it holds)
 * nth_hit=300                    (fire on every 300th call, counted from each app start ...)
 * times=1                        (... until it has fired this often in all, across restarts)
 * log=/path/to/run/faults.jsonl
 * until=MerchantBalance#write    (barrier: hold until another thread or worker returns from this method)
 * listen=7071                    (barrier: the UDP port partners signal to; 0 = only threads of this app count)
 * signal_to=worker-1:7071        (signal: where to send a datagram each time point returns)
 * arm_file=/path/to/run/armed    (calls count only once this file exists, such as after a warm-up; absent: from the start)
 * </pre>
 */
public record FaultPlan(String point, String when, String action, long nthHit, int times, long delayMs, Path log,
                        String until, int listen, String signalTo, Path armFile) {
    static final String ENV = "FAULTLINE_PLAN";
    static final Set<String> ACTIONS = Set.of("crash", "delay", "throw", "barrier", "signal");
    private static final String METHOD = "[\\w.$]+#\\w+";

    public FaultPlan {
        if (!point.equals(FaultAgent.BEFORE_ACK) && !point.matches(METHOD)) {
            throw new IllegalArgumentException("point must be before-ack or Class#method, not '" + point + "'");
        }
        if (!when.equals("before") && !when.equals("after")) throw new IllegalArgumentException("when must be before or after");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("unsupported action '" + action + "'");
        if (nthHit < 1) throw new IllegalArgumentException("nth_hit must be at least 1");
        if (times < 1) throw new IllegalArgumentException("times must be at least 1");
        if (action.equals("delay") && delayMs < 1) throw new IllegalArgumentException("a delay needs delay_ms");
        if (action.equals("barrier")) {
            if (until == null || !until.matches(METHOD)) throw new IllegalArgumentException("a barrier needs until=Class#method");
            if (delayMs < 1) throw new IllegalArgumentException("a barrier needs delay_ms, the longest it holds");
        }
        if (action.equals("signal") && (signalTo == null || !signalTo.matches("[\\w.-]+:\\d+"))) {
            throw new IllegalArgumentException("signal needs signal_to=host:port");
        }
    }

    public FaultPlan(String point, String when, String action, long nthHit, int times, long delayMs, Path log) {
        this(point, when, action, nthHit, times, delayMs, log, null, 0, null, null);
    }

    public FaultPlan(String point, String when, String action, long nthHit, int times, long delayMs, Path log,
                     String until, int listen, String signalTo) {
        this(point, when, action, nthHit, times, delayMs, log, until, listen, signalTo, null);
    }

    static FaultPlan load(Path file) throws IOException {
        var p = new Properties();
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new FaultPlan(required(p, "point"), p.getProperty("when", "before").trim(), p.getProperty("action", "crash").trim(),
            Long.parseLong(required(p, "nth_hit")), Integer.parseInt(required(p, "times")),
            Long.parseLong(p.getProperty("delay_ms", "0").trim()), Path.of(required(p, "log")),
            optional(p, "until"), Integer.parseInt(p.getProperty("listen", "0").trim()), optional(p, "signal_to"),
            optional(p, "arm_file") == null ? null : Path.of(optional(p, "arm_file")));
    }

    /** OrderService or dev.example.OrderService, from Class#method. */
    String typeName() {
        return typeOf(point);
    }

    String methodName() {
        return methodOf(point);
    }

    static String typeOf(String classAndMethod) {
        return classAndMethod.substring(0, classAndMethod.indexOf('#'));
    }

    static String methodOf(String classAndMethod) {
        return classAndMethod.substring(classAndMethod.indexOf('#') + 1);
    }

    /**
     * Faults still to come. A crash restarts the app and the agent with it, so the count of faults already
     * fired has to come from the fault log, not from memory.
     */
    int remaining() throws IOException {
        return times - FaultLog.count(log);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("plan is missing '" + key + "'");
        return value.trim();
    }

    private static String optional(Properties p, String key) {
        String value = p.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
