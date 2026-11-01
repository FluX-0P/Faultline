package dev.faultline.cli.doctor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dev.faultline.cli.app.ComposeConfig;
import dev.faultline.cli.plan.Hints;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.Endpoint;
import dev.faultline.cli.source.SpringConfig;

/**
 * The app's services in a Compose file and what they reach, read before anything boots. A service is the app's when it
 * builds a Spring Boot module of the repo (a folder with a build file and src/main/java: the Dockerfile's folder, else
 * the build context) or runs the image one of those builds. Each is judged by its configuration as it runs: the
 * module's files, its active profiles and its Compose environment. Its datasource and bootstrap servers name the
 * Postgres and Kafka services, by the names the app's containers reach them at.
 */
final class AppStack {
    private static final Pattern JDBC = Pattern.compile("jdbc:postgresql://([^/?:,]+)(?::(\\d+))?/([^?]+).*");
    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");
    private static final int POSTGRES_PORT = 5432;

    /**
     * A service of the app. {@code config} is how it runs; {@code recorded} the same without the values Compose filled in
     * from the shell or .env ({@code unrecorded}), which Faultline does not write down. {@code groups}: the consumer groups
     * its listeners join, when it is a consumer.
     */
    record AppService(ComposeConfig.Service service, Path module, String modulePath, SpringConfig config, SpringConfig recorded,
                      Map<String, String> recordedEnvironment, List<String> unrecorded, boolean consumer, Set<String> groups, int serverPort) {
        String name() {
            return service.name();
        }
    }

    /**
     * A database the app's services use: the Postgres service, its port inside Docker, the address and name the app uses,
     * and the app's credentials. {@code recordedPassword} is null when the password came from the shell or .env.
     */
    record Database(ComposeConfig.Service service, int port, String address, String name, String user, String password,
                    String recordedPassword, Set<String> usedBy) {}

    final ComposeConfig config;
    final List<AppService> app = new ArrayList<>();
    final List<Database> databases = new ArrayList<>();
    final List<String> kafkaAddresses = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    /** Services of the app the hints leave out. */
    final List<String> skipped = new ArrayList<>();
    /**
     * Other services the app's settings name by host, such as a schema registry or a load balancer in front of another
     * of its services: they must run for the app to work, though no depends_on says so.
     */
    final Set<String> reached = new TreeSet<>();
    ComposeConfig.Service kafka;

    private AppStack(ComposeConfig config) {
        this.config = config;
    }

    /**
     * @param config the Compose file as it runs
     * @param raw the same without interpolation, to tell the file's own values from the shell's
     */
    static AppStack read(Path repo, ComposeConfig config, ComposeConfig raw) throws IOException {
        return read(repo, config, raw, List.of());
    }

    /**
     * @param skip services to leave out, from the hints: of the app, or a helper off the journeys Faultline tests, such
     *             as a Kafka Connect whose connector a script registers by hand
     */
    static AppStack read(Path repo, ComposeConfig config, ComposeConfig raw, List<String> skip) throws IOException {
        var stack = new AppStack(config);
        var modules = new TreeMap<String, Path>();
        for (var s : config.services().values()) module(s, repo).ifPresent(m -> modules.put(s.name(), m));
        var images = new TreeMap<String, Path>();
        modules.forEach((name, m) -> {
            String image = config.services().get(name).image();
            if (!image.isEmpty()) images.put(image, m);
        });
        for (var s : config.services().values()) {
            if (!modules.containsKey(s.name()) && images.containsKey(s.image())) modules.put(s.name(), images.get(s.image()));
        }
        for (String name : skip) {
            if (modules.remove(name) == null && !config.services().containsKey(name)) {
                throw new IllegalArgumentException(Hints.FILE + ": skip names " + name + ", which is no service of the Compose file");
            }
            stack.skipped.add(name);
        }

        for (var e : modules.entrySet()) {
            var service = config.services().get(e.getKey());
            var rawEnvironment = raw.services().containsKey(e.getKey()) ? raw.services().get(e.getKey()).environment() : Map.<String, String>of();
            var recorded = new TreeMap<String, String>();
            var unrecorded = new ArrayList<String>();
            service.environment().forEach((k, v) -> {
                String before = rawEnvironment.getOrDefault(k, v);
                if (before.contains("$")) unrecorded.add(k);
                else recorded.put(k, v);
            });
            App module = App.read(e.getValue(), service.environment());
            var cfg = module.config();
            var listeners = module.code().endpoints().stream().filter(x -> x.kind() == Endpoint.Kind.KAFKA).toList();
            boolean consumer = !listeners.isEmpty() && !"false".equalsIgnoreCase(cfg.get("spring.kafka.listener.auto-startup").orElse(""));
            var groups = new TreeSet<String>();
            if (consumer) {
                for (Endpoint l : listeners) {
                    String group = l.groupId() != null ? l.groupId() : cfg.get("spring.kafka.consumer.group-id").orElse(null);
                    if (group != null) groups.add(group);
                }
            }
            int port = Integer.parseInt(cfg.get("server.port").orElse("8080").strip());
            String path = repo.relativize(e.getValue()).toString().replace('\\', '/');
            stack.app.add(new AppService(service, e.getValue(), path, cfg, SpringConfig.load(module.files(), recorded), recorded,
                List.copyOf(unrecorded), consumer, groups, port));
        }
        for (var s : stack.app) stack.reaches(s);
        var own = new TreeSet<String>(stack.app.stream().map(AppService::name).toList());
        if (stack.kafka != null) own.add(stack.kafka.name());
        stack.databases.forEach(d -> own.add(d.service().name()));
        for (var s : stack.app) {
            // resolved, since a URL is often built from other settings: http://${product-service.name}:80
            for (String host : hostsIn(s.config().asResolvedText())) {
                config.reachedAt(host).map(ComposeConfig.Service::name).filter(n -> !own.contains(n) && !stack.skipped.contains(n)).ifPresent(stack.reached::add);
            }
        }
        return stack;
    }

    private static final Pattern HOST = Pattern.compile("(?:^|[=/@,\\s])([A-Za-z][A-Za-z0-9_.-]*):(\\d{2,5})\\b");

    /** The host names a configuration names with a port, such as schema-registry in http://schema-registry:8081. */
    static Set<String> hostsIn(String config) {
        var hosts = new TreeSet<String>();
        HOST.matcher(config).results().forEach(m -> hosts.add(m.group(1)));
        return hosts;
    }

    /** The Spring Boot module a service builds, if it builds one of the repo's. */
    static Optional<Path> module(ComposeConfig.Service service, Path repo) {
        if (service.build() == null) return Optional.empty();
        var candidates = Stream.of(service.dockerfile() == null ? null : service.dockerfile().getParent(), service.build());
        return candidates.filter(d -> d != null && d.startsWith(repo) && isSpringModule(d)).findFirst();
    }

    static boolean isSpringModule(Path dir) {
        return BUILD_FILES.stream().anyMatch(f -> Files.isRegularFile(dir.resolve(f))) && Files.isDirectory(dir.resolve("src/main/java"));
    }

    /** Whether a service runs Postgres: its image, or the image its Dockerfile starts from. */
    static boolean isPostgres(ComposeConfig.Service service) {
        if (service.image().contains("postgres") || service.image().contains("postgis")) return true;
        if (service.dockerfile() == null || !Files.isRegularFile(service.dockerfile())) return false;
        try {
            return Files.readAllLines(service.dockerfile()).stream().map(String::strip)
                .anyMatch(l -> l.regionMatches(true, 0, "FROM ", 0, 5) && (l.contains("postgres") || l.contains("postgis")));
        } catch (IOException e) {
            return false;
        }
    }

    /** What the service's datasource and bootstrap servers point at. */
    private void reaches(AppService s) {
        s.config().get("spring.datasource.url").ifPresent(url -> {
            var m = JDBC.matcher(url.strip());
            if (!m.matches()) {
                notes.add(s.name() + "'s datasource is not a Postgres address Faultline can read: " + url);
                return;
            }
            String host = m.group(1);
            int port = m.group(2) == null ? POSTGRES_PORT : Integer.parseInt(m.group(2));
            var target = config.reachedAt(host);
            if (target.isEmpty()) {
                notes.add(s.name() + " reaches its database at " + host + ", which is no service of the Compose file");
                return;
            }
            if (!isPostgres(target.get())) {
                notes.add(s.name() + " reaches its database through " + target.get().name() + " (" + target.get().image() + "), not directly");
                return;
            }
            String address = host + ":" + port;
            String name = m.group(3);
            var known = databases.stream().filter(d -> d.address().equals(address) && d.name().equals(name)).findFirst();
            if (known.isPresent()) {
                known.get().usedBy().add(s.name());
                return;
            }
            String password = s.config().get("spring.datasource.password").orElse("");
            String recorded = s.recorded().get("spring.datasource.password").orElse("");
            databases.add(new Database(target.get(), port, address, name, s.config().get("spring.datasource.username").orElse(""), password,
                recorded.equals(password) ? password : null, new TreeSet<>(Set.of(s.name()))));
        });
        s.config().get("spring.kafka.bootstrap-servers").ifPresent(bootstrap -> {
            for (String a : Arrays.stream(bootstrap.split(",")).map(AppStack::withoutScheme).toList()) {
                String host = a.contains(":") ? a.substring(0, a.lastIndexOf(':')) : a;
                var target = config.reachedAt(host);
                if (target.isEmpty()) continue;
                if (kafka == null) kafka = target.get();
                if (kafka == target.get() && !kafkaAddresses.contains(a)) kafkaAddresses.add(a);
            }
        });
    }

    /** The app's services by name. */
    Map<String, AppService> byName() {
        var byName = new LinkedHashMap<String, AppService>();
        for (var s : app) byName.put(s.name(), s);
        return byName;
    }

    static String withoutScheme(String address) {
        String a = address.strip();
        int i = a.indexOf("://");
        return i >= 0 ? a.substring(i + 3) : a;
    }
}
