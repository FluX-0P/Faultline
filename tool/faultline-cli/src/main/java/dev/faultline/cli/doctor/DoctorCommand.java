package dev.faultline.cli.doctor;

import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import dev.faultline.cli.Faultline;
import dev.faultline.cli.Json;
import dev.faultline.cli.Out;
import dev.faultline.cli.Workspace;
import dev.faultline.cli.app.ComposeConfig;
import dev.faultline.cli.app.DockerStack;
import dev.faultline.cli.app.Environment;
import dev.faultline.cli.kafka.Topics;
import dev.faultline.cli.plan.Durations;
import dev.faultline.cli.plan.Hints;
import dev.faultline.cli.source.App;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

@Command(name = "doctor", mixinStandardHelpOptions = true,
    description = {"Check that Faultline can test a repo, and learn how it runs: .faultline/<app>/environment.json.",
        "Reads the repo for what Faultline covers (Spring Boot on Java 17+, @KafkaListener consumers, Postgres), then boots its",
        "Compose stack once: finds how to reach Kafka and each database from this machine, reads their tables, the topics and",
        "the consumer groups, and tears the stack down again. understand, scan and plan judge the app by what it wrote.",
        "Exit code 0: in scope and booted, 1: out of scope (with the reasons), 2: could not run."})
public final class DoctorCommand implements Callable<Integer> {
    static final String OVERRIDE = "compose.faultline.yml";
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);
    /** How often a service of the app that exited is started again, such as one that started before its database was up. */
    private static final int APP_RESTARTS = 2;
    private static final Duration GROUPS_WAIT = Duration.ofSeconds(60);
    private static final int LOG_LINES = 20;
    private static final Pattern COPY = Pattern.compile("(?i)^(?:COPY|ADD)\\s+(.+)$");
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{12,64}");
    /** An image with a tag or a digest after its last slash, such as zookeeper:3.4.14 or quay.io/x/y@sha256:... */
    private static final Pattern EXACT_IMAGE = Pattern.compile("[^\\s@]+(?::[\\w][\\w.-]*|@sha256:[0-9a-f]{64})");
    private static final Pattern V1_MANIFEST = Pattern.compile("Image (\\S+) Error[^\\n]*manifest\\.v1\\+prettyjws");
    private static final YAMLMapper YAML = YAMLMapper.builder().disable(YAMLWriteFeature.WRITE_DOC_START_MARKER).build();

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "REPO", description = "The app's repo, for example ../microservice-kafka")
    Path repoDir;

    @Option(names = "--compose", required = true, paramLabel = "FILE", description = "The Compose file that runs the app, such as docker/docker-compose.yml")
    String compose;

    @Option(names = "--profile", description = "The Compose profile the app's services are in")
    String profile;

    @Option(names = "--timeout", defaultValue = "5m", description = "How long the stack may take to be ready once it is built (default: ${DEFAULT-VALUE})")
    String timeout;

    @Option(names = "--keep", description = "Leave the stack running afterwards")
    boolean keep;

    private final List<String> files = new ArrayList<>();
    private final Thread shutdownHook = new Thread(this::downQuietly);
    private boolean up;

    /** A container of the stack as {@code docker compose ps} shows it. */
    record Container(String service, String state, String health, int exitCode) {}

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        Path repo = repoDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(repo)) throw new IllegalArgumentException("not a folder: " + repoDir);
        Duration wait = Durations.parse(timeout);

        var verdict = Scope.check(repo, App.read(repoDir));
        if (!verdict.found().isEmpty()) Out.line(out, "scope     %s", verdict.found());
        for (String note : verdict.notes()) Out.line(out, "note      %s", note);
        if (!verdict.out().isEmpty()) return outOfScope(out, repo, verdict.out());

        if (!Files.isRegularFile(Path.of(compose))) throw new IllegalArgumentException("no Compose file at " + compose);
        var hints = Hints.read(repo);
        var config = ComposeConfig.read(List.of(compose), profile, true);
        var stack = AppStack.read(repo, config, ComposeConfig.read(List.of(compose), profile, false), hints.skipped());
        if (stack.app.isEmpty()) {
            return outOfScope(out, repo, List.of("no service of " + compose + " builds the app from " + Out.path(repoDir)
                + ": they run prebuilt images, so the code Faultline reads may not be the code that runs"));
        }
        var images = hints.imageReplacements();
        checkImages(images, config, stack);
        config = config.withImages(images);
        for (var e : images.entrySet()) Out.line(out, "image     %s runs %s instead of %s (%s)", e.getKey(), e.getValue(), stack.config.services().get(e.getKey()).image(), Hints.FILE);
        Out.line(out, "compose   %s, project %s; the app: %s", compose, config.project(), stack.app.stream()
            .map(s -> s.name() + (s.modulePath().isEmpty() ? "" : " (" + s.modulePath() + ")")).collect(joining(", ")));
        for (String s : stack.skipped) Out.line(out, "skip      %s is left out (%s)", s, Hints.FILE);
        for (String note : stack.notes) Out.line(out, "note      %s", note);
        if (stack.databases.isEmpty()) throw new IllegalStateException("none of the app's services reaches a Postgres service of " + compose + " (see above)");
        if (stack.kafka == null) {
            throw new IllegalStateException("none of the app's services sets spring.kafka.bootstrap-servers to a service of " + compose);
        }

        // how Faultline reaches Kafka and the databases from this machine
        var stackPorts = new TreeSet<Integer>();
        config.services().values().forEach(s -> s.ports().forEach(p -> {
            if (p.published() != null) stackPorts.add(p.published());
        }));
        var reach = new Reach(port -> !stackPorts.contains(port) && Ports.isFree(port));
        images.forEach(reach::replaceImage);
        var kafka = reach.kafka(config.services().get(stack.kafka.name())).orElse(null);
        if (kafka == null) {
            var hint = hints.kafka();
            if (hint == null || hint.bootstrap() == null) {
                throw new IllegalStateException("cannot reach Kafka (%s, %s) from this machine: Faultline adds a listener only to apache/kafka, "
                    .formatted(stack.kafka.name(), stack.kafka.image()) + "confluentinc/cp-kafka, bitnami/kafka and wurstmeister/kafka, from their "
                    + "listener settings. Publish one for this machine and set kafka: {bootstrap: localhost:PORT} in " + Hints.FILE);
            }
            kafka = new Reach.Address(hint.bootstrap(), "set in " + Hints.FILE);
        }
        Out.line(out, "reach     kafka at %s: %s, %s", String.join(", ", stack.kafkaAddresses), kafka.host(), kafka.how());
        var hosts = new LinkedHashMap<AppStack.Database, Reach.Address>();
        for (var d : stack.databases) {
            var address = reach.database(d.service(), d.port());
            hosts.put(d, address);
            Out.line(out, "          %s at %s: %s, %s", d.name(), d.address(), address.host(), address.how());
        }

        Path workspace = Workspace.forApp(repoDir);
        Files.createDirectories(workspace);
        Path overrideFile = workspace.resolve(OVERRIDE);
        files.add(compose);
        if (reach.override().isEmpty()) {
            Files.deleteIfExists(overrideFile);
        } else {
            Files.writeString(overrideFile, "# written by faultline doctor: how Faultline reaches the app's Kafka and databases from this machine,"
                + " and the images " + Hints.FILE + " replaces\n" + YAML.writeValueAsString(reach.override()), StandardCharsets.UTF_8);
            files.add(Out.path(overrideFile));
            Out.line(out, "override  %s", Out.path(overrideFile));
        }

        // what boots: the app's services, what they reach, and what they depend on
        var named = new ArrayList<>(stack.app.stream().map(AppStack.AppService::name).toList());
        named.add(stack.kafka.name());
        stack.databases.forEach(d -> named.add(d.service().name()));
        var boot = config.withDependencies(named);
        checkSkipped(stack.skipped, boot, config);
        if (!portsFree(out, config, boot, reach.publishedPorts())) return Faultline.ERROR;
        if (!jarsBuilt(out, repo, stack)) return Faultline.ERROR;

        try {
            // container IDs only: Compose's warnings come through on the same output
            if (compose(List.of("ps", "-q")).lines().anyMatch(l -> CONTAINER_ID.matcher(l.strip()).matches())) {
                Out.line(out, "note      %s is running already: doctor recreates it%s", config.project(), keep ? "" : ", and removes it at the end (--keep leaves it up)");
            }
            Out.line(out, "boot      building and starting %d services: %s ...", boot.size(), String.join(", ", boot));
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            up = true;
            long start = System.nanoTime();
            var args = new ArrayList<>(List.of("up", "-d", "--build", "--force-recreate", "--renew-anon-volumes"));
            args.addAll(boot);
            try {
                compose(args, BUILD_TIMEOUT);
            } catch (IllegalStateException e) {
                throw unpullable(e, config).orElse(e);
            }
            Out.line(out, "          built and started after %.1fs, waiting until it is ready ...", (System.nanoTime() - start) / 1e9);
            waitUntilReady(out, stack, kafka.host(), hosts, wait);
            Out.line(out, "          ready after %.1fs", (System.nanoTime() - start) / 1e9);

            var environment = read(out, repo, config, stack, kafka, hosts);
            Path file = workspace.resolve(Environment.FILE);
            Json.writePretty(file, environment);
            Out.line(out, "wrote     %s", Out.path(file));
        } finally {
            if (keep) {
                Out.line(out, "kept      the stack is running; stop it with docker compose %s down", files.stream().map(f -> "-f " + f).collect(joining(" ")));
            } else {
                down(out);
            }
        }
        Out.line(out, "result    IN SCOPE: %s boots, and Faultline reaches its Kafka and %d database%s", repo.getFileName(), stack.databases.size(),
            stack.databases.size() == 1 ? "" : "s");
        if (files.size() > 1) {
            var infra = new TreeSet<String>(List.of(stack.kafka.name()));
            stack.databases.forEach(d -> infra.add(d.service().name()));
            Out.line(out, "note      a run needs them up with Faultline's override: docker compose %s up -d %s",
                files.stream().map(f -> "-f " + f).collect(joining(" ")), String.join(" ", infra));
        }
        Out.line(out, "next      ./faultline understand %s", Out.path(repoDir));
        return Faultline.PASS;
    }

    /** The hints may give helper services other images, never the app's own services, and only exact versions. */
    static void checkImages(Map<String, String> images, ComposeConfig config, AppStack stack) {
        for (var e : images.entrySet()) {
            String where = Hints.FILE + ": images." + e.getKey();
            if (!config.services().containsKey(e.getKey())) throw new IllegalArgumentException(where + ": no service of that name in the Compose file");
            if (stack.byName().containsKey(e.getKey())) {
                throw new IllegalArgumentException(where + ": a service of the app keeps its own build, since Faultline tests the app's own code");
            }
            if (!EXACT_IMAGE.matcher(e.getValue()).matches() || e.getValue().endsWith(":latest")) {
                throw new IllegalArgumentException(where + ": name an exact version, such as zookeeper:3.4.14, not " + e.getValue());
            }
        }
    }

    /** A service left out must not be one that another service in the boot depends on: Compose would start it anyway. */
    static void checkSkipped(List<String> skipped, Set<String> boot, ComposeConfig config) {
        for (String s : skipped) {
            var dependents = boot.stream().filter(b -> config.services().get(b).dependsOn().contains(s)).toList();
            if (!dependents.isEmpty()) {
                throw new IllegalArgumentException(Hints.FILE + ": cannot skip " + s + ": " + String.join(", ", dependents) + " depends on it, so Compose starts it anyway");
            }
        }
    }

    /** A pull that failed because Docker no longer reads the image's old v1 manifest, said so with the hint that helps. */
    static Optional<IllegalStateException> unpullable(IllegalStateException e, ComposeConfig config) {
        var m = V1_MANIFEST.matcher(String.valueOf(e.getMessage()));
        if (!m.find()) return Optional.empty();
        String image = m.group(1);
        String service = config.services().values().stream().filter(s -> s.image().equals(image)).map(ComposeConfig.Service::name).findFirst().orElse("SERVICE");
        return Optional.of(new IllegalStateException("%s (service %s) cannot be pulled: it was pushed with Docker's old v1 manifest, which Docker no longer reads. Give the service another image in %s, such as images: {%s: IMAGE:VERSION}"
            .formatted(image, service, Hints.FILE, service)));
    }

    private static int outOfScope(PrintWriter out, Path repo, List<String> reasons) {
        for (String reason : reasons) Out.line(out, "out       %s", reason);
        Out.line(out, "result    OUT OF SCOPE: Faultline cannot test %s (see above)", repo.getFileName());
        return Faultline.FAIL;
    }

    /**
     * Whether every host port the stack publishes is free, or held by the stack itself. A taken port is named with the
     * container that holds it, never remapped: the run would talk to another stack's database.
     */
    private static boolean portsFree(PrintWriter out, ComposeConfig config, Set<String> boot, Set<Integer> overridePorts) throws Exception {
        var wanted = new LinkedHashMap<Integer, String>();
        for (String name : boot) {
            for (var p : config.services().get(name).ports()) {
                if (p.published() != null) wanted.putIfAbsent(p.published(), name);
            }
        }
        overridePorts.forEach(p -> wanted.putIfAbsent(p, "Faultline's override"));
        var containers = Ports.containers();
        boolean free = true;
        for (var e : wanted.entrySet()) {
            int port = e.getKey();
            var holder = Ports.holder(port, containers);
            if (holder.isPresent() && holder.get().project().equals(config.project())) continue;
            if (holder.isEmpty() && Ports.isFree(port)) continue;
            free = false;
            if (holder.isPresent()) {
                var h = holder.get();
                Out.line(out, "ports     %d (%s) is taken by container %s: stop it with docker stop %s%s", port, e.getValue(), h.name(), h.name(),
                    h.project().isEmpty() ? "" : ", or its whole stack with docker compose -p " + h.project() + " stop");
            } else {
                Out.line(out, "ports     %d (%s) is taken by a program on this machine, not a container: stop it first", port, e.getValue());
            }
        }
        if (free) Out.line(out, "ports     %d free: %s", wanted.size(), wanted.keySet().stream().map(String::valueOf).collect(joining(", ")));
        return free;
    }

    /** Whether the jars the app's Dockerfiles copy in are there: a Dockerfile that copies target/app.jar needs the app built first. */
    private static boolean jarsBuilt(PrintWriter out, Path repo, AppStack stack) throws IOException {
        boolean built = true;
        for (var s : stack.app) {
            for (String jar : missingJars(s.service())) {
                built = false;
                Out.line(out, "build     %s's Dockerfile copies %s, which is not built yet: build the app first, such as %s", s.name(), jar, buildHint(repo, s.module()));
            }
        }
        return built;
    }

    static List<String> missingJars(ComposeConfig.Service service) throws IOException {
        if (service.dockerfile() == null || !Files.isRegularFile(service.dockerfile())) return List.of();
        var missing = new ArrayList<String>();
        for (String line : Files.readAllLines(service.dockerfile())) {
            var m = COPY.matcher(line.strip());
            if (!m.matches() || m.group(1).contains("[")) continue;
            var tokens = Arrays.stream(m.group(1).split("\\s+")).toList();
            if (tokens.stream().anyMatch(t -> t.startsWith("--from"))) continue;
            var sources = tokens.stream().filter(t -> !t.startsWith("--")).toList();
            for (String source : sources.subList(0, Math.max(0, sources.size() - 1))) {
                if (source.endsWith(".jar") && !present(service.build(), source)) missing.add(source);
            }
        }
        return missing;
    }

    private static boolean present(Path context, String source) throws IOException {
        String s = source.startsWith("./") ? source.substring(2) : source;
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && s.substring(0, slash).contains("*")) return true;   // cannot tell; the build will
        Path dir = slash < 0 ? context : context.resolve(s.substring(0, slash));
        if (!Files.isDirectory(dir)) return false;
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + s.substring(slash + 1));
        try (var listed = Files.list(dir)) {
            return listed.anyMatch(f -> matcher.matches(f.getFileName()));
        }
    }

    /** The build command for a module: its Maven or Gradle wrapper, in the nearest folder up to the repo that has one. */
    private static String buildHint(Path repo, Path module) {
        for (Path dir = module; dir != null && dir.startsWith(repo); dir = dir.getParent()) {
            String where = Out.path(repo.relativize(dir));
            String cd = where.isEmpty() ? "" : "cd " + where + " && ";
            if (Files.isRegularFile(dir.resolve("mvnw"))) return "(" + cd + "./mvnw -q package -DskipTests)";
            if (Files.isRegularFile(dir.resolve("gradlew"))) return "(" + cd + "./gradlew assemble)";
        }
        return "with its own build (mvn package or gradle assemble)";
    }

    /**
     * Waits until every container is running (and healthy, when it has a health check) or, for a one-shot helper, has
     * exited cleanly; the app's services answer HTTP on their published port; and Kafka and every database answer from
     * this machine. A service of the app that exits is started again a couple of times, as a restart policy would.
     */
    private void waitUntilReady(PrintWriter out, AppStack stack, String kafkaHost, Map<AppStack.Database, Reach.Address> hosts, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        var restarts = new HashMap<String, Integer>();
        var app = stack.byName();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            while (true) {
                var waiting = new ArrayList<String>();
                for (var c : ps()) {
                    switch (c.state()) {
                        case "running" -> {
                            if (!c.health().isEmpty() && !c.health().equals("healthy")) waiting.add(c.service() + " (" + c.health() + ")");
                        }
                        case "exited", "dead" -> {
                            if (c.exitCode() == 0 && !app.containsKey(c.service())) continue;   // a one-shot helper that has done its job
                            if (app.containsKey(c.service()) && restarts.merge(c.service(), 1, Integer::sum) <= APP_RESTARTS) {
                                Out.line(out, "          %s exited (%d), maybe before what it needs was ready: starting it again", c.service(), c.exitCode());
                                compose(List.of("start", c.service()));
                                waiting.add(c.service() + " (starting again)");
                                continue;
                            }
                            printLogs(out, c.service());
                            throw new IllegalStateException(c.service() + " exited with code " + c.exitCode() + " (its last log lines are above)");
                        }
                        default -> waiting.add(c.service() + " (" + c.state() + ")");
                    }
                }
                for (var s : stack.app) {
                    var port = s.service().published(s.serverPort());
                    if (port.isPresent() && waiting.stream().noneMatch(w -> w.startsWith(s.name() + " ")) && !answers(http, port.get())) {
                        waiting.add(s.name() + " (not answering on port " + port.get() + ")");
                    }
                }
                if (waiting.isEmpty()) {
                    if (!kafkaAnswers(kafkaHost)) waiting.add("kafka (not answering at " + kafkaHost + ")");
                    for (var e : hosts.entrySet()) {
                        if (!databaseAnswers(e.getKey(), e.getValue())) waiting.add(e.getKey().name() + " (not answering at " + e.getValue().host() + ")");
                    }
                }
                if (waiting.isEmpty()) return;
                if (System.nanoTime() > deadline) {
                    for (String w : waiting) {
                        String service = w.substring(0, w.indexOf(' '));
                        if (stack.config.services().containsKey(service)) printLogs(out, service);
                    }
                    throw new IllegalStateException("the stack was not ready within " + timeout.toSeconds() + "s: still waiting for "
                        + String.join(", ", waiting) + " (--timeout waits longer)");
                }
                Thread.sleep(1000);
            }
        }
    }

    /** The live facts: each database's tables, the topics and the consumer groups. */
    private Environment read(PrintWriter out, Path repo, ComposeConfig config, AppStack stack, Reach.Address kafka,
                             Map<AppStack.Database, Reach.Address> hosts) throws Exception {
        var databases = new ArrayList<Environment.Database>();
        for (var e : hosts.entrySet()) {
            var d = e.getKey();
            try (var conn = connect(d, e.getValue())) {
                var tables = LiveSchema.read(conn);
                Out.line(out, "schema    %s: %s", d.name(), tables.isEmpty() ? "no tables" : tables.stream()
                    .map(t -> t.name() + " (" + t.rows() + " rows)").collect(joining(", ")));
                databases.add(new Environment.Database(d.service().name(), d.address(), d.name(), e.getValue().host(), d.user(), d.recordedPassword(),
                    List.copyOf(d.usedBy()), tables));
            }
        }

        var expected = new TreeSet<String>();
        stack.app.forEach(s -> expected.addAll(s.groups()));
        List<String> groups;
        Map<String, Integer> partitions;
        try (var topics = new Topics(kafka.host())) {
            long deadline = System.nanoTime() + GROUPS_WAIT.toNanos();
            groups = topics.groups();
            while (!groups.containsAll(expected) && System.nanoTime() < deadline) {
                Thread.sleep(1000);
                groups = topics.groups();
            }
            partitions = topics.partitions();
        }
        var missing = new TreeSet<>(expected);
        missing.removeAll(groups);
        Out.line(out, "kafka     topics: %s", partitions.isEmpty() ? "none yet" : partitions.entrySet().stream()
            .map(t -> t.getKey() + " (" + t.getValue() + ")").collect(joining(", ")));
        Out.line(out, "          groups: %s", groups.isEmpty() ? "none yet" : String.join(", ", groups));
        if (!missing.isEmpty()) Out.line(out, "note      the listeners' groups %s did not show up within %ds", String.join(", ", missing), GROUPS_WAIT.toSeconds());

        var services = stack.app.stream().map(s -> new Environment.Service(s.name(), s.modulePath(), s.recordedEnvironment(), s.unrecorded(), s.consumer())).toList();
        return new Environment(repo.getFileName().toString(),
            new Environment.Compose(compose, profile, config.project(), files.size() > 1 ? files.get(1) : null), services,
            new Environment.Kafka(stack.kafka.name(), List.copyOf(stack.kafkaAddresses), kafka.host(), kafka.how()), databases,
            partitions.entrySet().stream().map(t -> new Environment.Topic(t.getKey(), t.getValue())).toList(), groups, Instant.now().toString());
    }

    private static Connection connect(AppStack.Database d, Reach.Address host) throws Exception {
        var properties = new Properties();
        properties.setProperty("user", d.user());
        properties.setProperty("password", d.password());
        properties.setProperty("connectTimeout", "5");
        return DriverManager.getConnection("jdbc:postgresql://" + host.host() + "/" + d.name(), properties);
    }

    private static boolean databaseAnswers(AppStack.Database d, Reach.Address host) {
        try (var conn = connect(d, host)) {
            return conn.isValid(5);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean kafkaAnswers(String host) {
        try (var topics = new Topics(host)) {
            topics.partitions();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean answers(HttpClient http, int port) throws InterruptedException {
        try {
            http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private List<Container> ps() throws Exception {
        var containers = new ArrayList<Container>();
        for (JsonNode n : jsonLines(compose(List.of("ps", "-a", "--format", "json")))) {
            containers.add(new Container(n.path("Service").asString(""), n.path("State").asString(""), n.path("Health").asString(""), n.path("ExitCode").asInt(0)));
        }
        return containers;
    }

    /** {@code docker compose ps --format json}: one object per line, or one array in older versions; warnings skipped. */
    static List<JsonNode> jsonLines(String text) {
        var nodes = new ArrayList<JsonNode>();
        for (String line : text.lines().map(String::strip).toList()) {
            if (line.startsWith("[")) Json.MAPPER.readTree(line).forEach(nodes::add);
            else if (line.startsWith("{")) nodes.add(Json.MAPPER.readTree(line));
        }
        return nodes;
    }

    private void printLogs(PrintWriter out, String service) {
        try {
            Out.line(out, "logs      %s, last %d lines:", service, LOG_LINES);
            for (String line : compose(List.of("logs", "--no-color", "--tail", String.valueOf(LOG_LINES), service)).lines().toList()) {
                Out.line(out, "          %s", line);
            }
        } catch (Exception e) {
            Out.line(out, "          (cannot read them: %s)", e.getMessage());
        }
    }

    /** Stops and removes every container of the stack, its anonymous volumes and its network; named volumes stay. */
    private void down(PrintWriter out) {
        if (!up) return;
        try {
            compose(List.of("rm", "-s", "-f", "-v"));
            compose(List.of("down", "--remove-orphans"));
            Out.line(out, "down      stopped and removed the stack");
        } catch (Exception e) {
            Out.line(out, "warning   could not remove the stack: %s", e.getMessage());
        }
        up = false;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // already shutting down
        }
    }

    private void downQuietly() {
        if (keep || !up) return;
        try {
            compose(List.of("rm", "-s", "-f", "-v"));
            compose(List.of("down", "--remove-orphans"));
        } catch (Exception e) {
            // nothing more to do while shutting down
        }
    }

    private String compose(List<String> args) throws IOException, InterruptedException {
        return compose(args, Duration.ofMinutes(5));
    }

    private String compose(List<String> args, Duration timeout) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("compose"));
        for (String f : files) command.addAll(List.of("-f", f));
        if (profile != null) command.addAll(List.of("--profile", profile));
        command.addAll(args);
        return DockerStack.docker(command, timeout);
    }
}
