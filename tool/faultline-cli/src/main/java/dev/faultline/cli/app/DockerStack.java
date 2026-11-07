package dev.faultline.cli.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.trace.Traces;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * The app as Docker Compose services, recreated for each run with that run's settings: Spring settings through
 * SPRING_APPLICATION_JSON, and the fault agent on one service. It goes through the docker CLI, which already knows
 * how to reach Docker Desktop or a Linux daemon, so Faultline needs no Docker library.
 */
public final class DockerStack implements AutoCloseable {
    public static final String RUN_DIR_IN_CONTAINER = "/faultline/run";
    static final String AGENT_IN_CONTAINER = "/faultline/agent.jar";
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{12,64}");
    static final String OVERRIDE_FILE = "compose.override.yml";
    private static final long COMMAND_TIMEOUT_MINUTES = 5;
    private static final YAMLMapper YAML = YAMLMapper.builder().disable(YAMLWriteFeature.WRITE_DOC_START_MARKER).build();

    private final Plan.Compose compose;
    private final Path runDir;
    private final Path override;
    private final Thread shutdownHook = new Thread(this::removeQuietly);
    private boolean up;
    /** The OpenTelemetry agent jar every service runs with, when the run keeps traces; else null. */
    private Path tracesAgent;

    public DockerStack(Plan.Compose compose, Path runDir) {
        this.compose = compose;
        this.runDir = runDir;
        this.override = runDir.resolve(OVERRIDE_FILE);
    }

    /**
     * (Re)builds and starts every service with these settings. Call {@link #removeLeftovers()} first.
     * Each service in {@code agents} also gets the agent with that environment, the agent jar and the run folder mounted.
     */
    public void up(Map<String, String> settings, Map<String, Map<String, String>> agents, Path agentJar) throws IOException, InterruptedException {
        // a service the agent may crash comes back by itself, like Kubernetes would restart it, unless its own file says how
        var restart = new ArrayList<String>();
        if (!agents.isEmpty()) {
            var config = ComposeConfig.jsonOf(compose(List.of("config", "--format", "json"))).path("services");
            agents.keySet().stream().filter(s -> config.path(s).path("restart").asString("").isEmpty()).forEach(restart::add);
        }
        writeOverride(settings, agents, agentJar, restart);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        up = true;
        var args = new ArrayList<>(List.of("up", "-d", "--build", "--force-recreate", "--no-deps"));
        args.addAll(compose.services());
        compose(args);
    }

    void writeOverride(Map<String, String> settings, Map<String, Map<String, String>> agents, Path agentJar) throws IOException {
        writeOverride(settings, agents, agentJar, List.of());
    }

    /** @param restart services that get restart: on-failure */
    /** Every service also runs the OpenTelemetry agent, sending its traces to Faultline's observability container. */
    public void keepTraces(Path otelAgentJar) {
        this.tracesAgent = otelAgentJar;
    }

    void writeOverride(Map<String, String> settings, Map<String, Map<String, String>> agents, Path agentJar, List<String> restart) throws IOException {
        var services = new LinkedHashMap<String, Object>();
        String json = Json.MAPPER.writeValueAsString(settings);
        for (String name : compose.services()) {
            var env = new LinkedHashMap<String, String>();
            env.put("SPRING_APPLICATION_JSON", json);
            var service = new LinkedHashMap<String, Object>();
            var volumes = new ArrayList<Map<String, Object>>();
            var javaAgents = new ArrayList<String>();
            if (agents.containsKey(name)) {
                javaAgents.add("-javaagent:" + AGENT_IN_CONTAINER);
                env.putAll(agents.get(name));
                volumes.add(Map.of("type", "bind", "source", Out.path(agentJar.toAbsolutePath()), "target", AGENT_IN_CONTAINER, "read_only", true));
                volumes.add(Map.of("type", "bind", "source", Out.path(runDir.toAbsolutePath()), "target", RUN_DIR_IN_CONTAINER));
            }
            if (tracesAgent != null) {
                // two -javaagent flags work together; the services reach the container's OTLP port through this machine
                javaAgents.add("-javaagent:" + Traces.AGENT_IN_CONTAINER);
                env.putAll(Traces.environment(name));
                volumes.add(Map.of("type", "bind", "source", Out.path(tracesAgent.toAbsolutePath()), "target", Traces.AGENT_IN_CONTAINER, "read_only", true));
                service.put("extra_hosts", List.of("host.docker.internal:host-gateway"));
            }
            if (!javaAgents.isEmpty()) env.put("JAVA_TOOL_OPTIONS", String.join(" ", javaAgents));
            if (!volumes.isEmpty()) service.put("volumes", volumes);
            service.put("environment", env);
            if (restart.contains(name)) service.put("restart", "on-failure");
            services.put(name, service);
        }
        Files.writeString(override, "# written by faultline run for this run only\n" + YAML.writeValueAsString(Map.of("services", services)));
    }

    public void kill(String service) throws IOException, InterruptedException {
        compose(List.of("kill", "-s", "SIGKILL", service));
    }

    public void start(String service) throws IOException, InterruptedException {
        compose(List.of("start", service));
    }

    public void pause(String service) throws IOException, InterruptedException {
        compose(List.of("pause", service));
    }

    public void unpause(String service) throws IOException, InterruptedException {
        compose(List.of("unpause", service));
    }

    /** Empties a Redis service: what a reset does to a cache the app keeps state in. */
    public void flush(String service) throws IOException, InterruptedException {
        compose(List.of("exec", "-T", service, "redis-cli", "FLUSHALL"));
    }

    public void restart(String service) throws IOException, InterruptedException {
        compose(List.of("restart", service));
    }

    /** How often Docker restarted the services after they died on their own, such as after a planned crash. */
    public int restarts() throws IOException, InterruptedException {
        int total = 0;
        for (String service : compose.services()) {
            var id = containerIds(compose(List.of("ps", "-a", "-q", service))).stream().findFirst();
            if (id.isEmpty()) continue;
            total += Integer.parseInt(docker(List.of("inspect", "-f", "{{.RestartCount}}", id.get())).strip());
        }
        return total;
    }

    /** The container IDs in what {@code docker compose ps -q} printed, without the warnings Compose printed with them. */
    public static List<String> containerIds(String output) {
        return output.lines().map(String::strip).filter(l -> CONTAINER_ID.matcher(l).matches()).toList();
    }

    /** Saves every service's output to app.log, then stops and removes the services. */
    @Override
    public void close() {
        if (!up) return;
        try {
            var args = new ArrayList<>(List.of("logs", "--no-color", "--timestamps"));
            args.addAll(compose.services());
            Files.writeString(runDir.resolve("app.log"), compose(args), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // the run's results matter more than its logs
        }
        removeQuietly();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // already shutting down
        }
        up = false;
    }

    /** Stops and removes the services, such as ones an aborted run left behind, so their consumers leave the group. */
    public void removeLeftovers() throws IOException, InterruptedException {
        var args = new ArrayList<>(List.of("rm", "-s", "-f"));
        args.addAll(compose.services());
        compose(args);
    }

    private void removeQuietly() {
        try {
            removeLeftovers();
        } catch (Exception e) {
            // nothing more to do; the next run removes leftovers first
        }
    }

    private String compose(List<String> args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("compose"));
        for (String file : compose.files()) command.addAll(List.of("-f", file));
        if (Files.exists(override)) command.addAll(List.of("-f", override.toAbsolutePath().toString()));
        if (compose.profile() != null) command.addAll(List.of("--profile", compose.profile()));
        command.addAll(args);
        return docker(command);
    }

    public static String docker(List<String> args) throws IOException, InterruptedException {
        return docker(args, Duration.ofMinutes(COMMAND_TIMEOUT_MINUTES));
    }

    /** Runs the docker CLI and returns what it printed; throws with the last lines it printed when it fails. */
    public static String docker(List<String> args, Duration timeout) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("docker"));
        command.addAll(args);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getOutputStream().close();
        byte[] output = p.getInputStream().readAllBytes();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("docker " + String.join(" ", args) + " did not finish");
        }
        String text = new String(output, StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            String tail = text.lines().skip(Math.max(0, text.lines().count() - 8)).reduce("", (a, b) -> a + "\n  " + b);
            throw new IllegalStateException("docker " + String.join(" ", args.subList(0, Math.min(args.size(), 8))) + " ... failed:" + tail);
        }
        return text;
    }
}
