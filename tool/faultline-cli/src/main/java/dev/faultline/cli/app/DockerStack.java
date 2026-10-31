package dev.faultline.cli.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.plan.Plan;
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
    static final String OVERRIDE_FILE = "compose.override.yml";
    private static final long COMMAND_TIMEOUT_MINUTES = 5;
    private static final YAMLMapper YAML = YAMLMapper.builder().disable(YAMLWriteFeature.WRITE_DOC_START_MARKER).build();

    private final Plan.Compose compose;
    private final Path runDir;
    private final Path override;
    private final Thread shutdownHook = new Thread(this::removeQuietly);
    private boolean up;

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
        writeOverride(settings, agents, agentJar);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        up = true;
        var args = new ArrayList<>(List.of("up", "-d", "--build", "--force-recreate", "--no-deps"));
        args.addAll(compose.services());
        compose(args);
    }

    void writeOverride(Map<String, String> settings, Map<String, Map<String, String>> agents, Path agentJar) throws IOException {
        var services = new LinkedHashMap<String, Object>();
        String json = Json.MAPPER.writeValueAsString(settings);
        for (String name : compose.services()) {
            var env = new LinkedHashMap<String, String>();
            env.put("SPRING_APPLICATION_JSON", json);
            var service = new LinkedHashMap<String, Object>();
            if (agents.containsKey(name)) {
                env.put("JAVA_TOOL_OPTIONS", "-javaagent:" + AGENT_IN_CONTAINER);
                env.putAll(agents.get(name));
                service.put("volumes", List.of(
                    Map.of("type", "bind", "source", Out.path(agentJar.toAbsolutePath()), "target", AGENT_IN_CONTAINER, "read_only", true),
                    Map.of("type", "bind", "source", Out.path(runDir.toAbsolutePath()), "target", RUN_DIR_IN_CONTAINER)));
            }
            service.put("environment", env);
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

    public void restart(String service) throws IOException, InterruptedException {
        compose(List.of("restart", service));
    }

    /** How often Docker restarted the services after they died on their own, such as after a planned crash. */
    public int restarts() throws IOException, InterruptedException {
        int total = 0;
        for (String service : compose.services()) {
            String id = compose(List.of("ps", "-a", "-q", service)).strip();
            if (id.isEmpty()) continue;
            total += Integer.parseInt(docker(List.of("inspect", "-f", "{{.RestartCount}}", id.lines().findFirst().orElseThrow())).strip());
        }
        return total;
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
        var command = new ArrayList<>(List.of("compose", "-f", compose.file()));
        if (Files.exists(override)) command.addAll(List.of("-f", override.toAbsolutePath().toString()));
        if (compose.profile() != null) command.addAll(List.of("--profile", compose.profile()));
        command.addAll(args);
        return docker(command);
    }

    static String docker(List<String> args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("docker"));
        command.addAll(args);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getOutputStream().close();
        byte[] output = p.getInputStream().readAllBytes();
        if (!p.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
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
