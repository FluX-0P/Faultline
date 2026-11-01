package dev.faultline.cli.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import dev.faultline.cli.Json;
import tools.jackson.core.JacksonException;

/**
 * .faultline/{app}/environment.json: what {@code faultline doctor} learned about the app as it runs from its Compose
 * file. Which services are the app and which module each one builds, the environment they run it with, how Faultline
 * reaches Kafka and each database from this machine, the live schema of each database as the app left it after
 * starting, and the topics and consumer groups. understand, scan and plan judge the app by it when it is there.
 *
 * @param checked when doctor booted the stack, as an ISO-8601 instant
 */
public record Environment(String app, Compose compose, List<Service> services, Kafka kafka, List<Database> databases,
                          List<Topic> topics, List<String> groups, String checked) {
    public static final String FILE = "environment.json";
    /** The prompt section that shows the live schema. */
    public static final String SCHEMA_TITLE = "Database tables, as the running app made them (read by faultline doctor)";
    private static final Pattern JDBC = Pattern.compile("jdbc:postgresql://([^/?]+)/([^?]*)(\\?.*)?");
    private static final int POSTGRES_PORT = 5432;

    /**
     * The Compose file doctor booted, and Faultline's own override next to it (null when none was needed): published
     * database ports and a Kafka listener for this machine. Every command that runs the stack adds the override.
     */
    public record Compose(String file, String profile, String project, String override) {}

    /**
     * A service of the app. {@code module} is the folder of the Spring Boot app it builds, from the repo's folder ("" for
     * the repo itself). Its {@code environment} leaves out values Compose fills in from your shell or .env, since they may
     * be secrets; their names are in {@code unrecorded}. {@code consumer}: its Kafka listeners are on.
     */
    public record Service(String name, String module, Map<String, String> environment, List<String> unrecorded, boolean consumer) {}

    /**
     * How Faultline reaches Kafka: the {@code addresses} the app's services use inside Docker, the {@code host} address on
     * this machine, and {@code how} it got one (a published listener, a listener Faultline's override adds, or the hints).
     */
    public record Kafka(String service, List<String> addresses, String host, String how) {}

    /**
     * A Postgres database: the address the app's services use for it inside Docker, the address on this machine, the
     * credentials the app's own configuration uses, and its tables after the app has started.
     */
    public record Database(String service, String address, String name, String host, String user, String password,
                           List<String> usedBy, List<Table> tables) {
        public String hostUrl() {
            return "jdbc:postgresql://" + host + "/" + name;
        }
    }

    /**
     * A table (schema-qualified unless it is in public), its columns in order, and the rows it had after the app started.
     * For a table with rows and a primary key of one column, {@code key} names it and {@code keys} has its first values,
     * so a request can refer to rows that exist (null when there are none).
     */
    public record Table(String name, List<Column> columns, long rows, String key, List<String> keys) {
        public static final int KEYS = 20;

        public Table(String name, List<Column> columns, long rows) {
            this(name, columns, rows, null, null);
        }
    }

    public record Column(String name, String type) {}

    public record Topic(String name, int partitions) {}

    /** The environment doctor wrote for the app whose files Faultline keeps in this folder, if it has run. */
    public static Optional<Environment> read(Path workspace) {
        Path file = workspace.resolve(FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(Json.MAPPER.readValue(file.toFile(), Environment.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot read " + file + ": " + e.getOriginalMessage() + " (run ./faultline doctor again)");
        }
    }

    /**
     * The environment of the service that runs the module, to judge its configuration by: a consumer first, since
     * Faultline tests what happens to messages, then by name. Empty when no service builds exactly this module.
     */
    public Map<String, String> runtimeOf(String module) {
        return services.stream().filter(s -> s.module().equals(module))
            .min(Comparator.comparing((Service s) -> !s.consumer()).thenComparing(Service::name))
            .map(Service::environment).orElse(Map.of());
    }

    /** The services that run the module or one inside it ("" is every service of the repo). */
    public Set<String> servicesIn(String module) {
        return services.stream().filter(s -> module.isEmpty() || s.module().equals(module) || s.module().startsWith(module + "/"))
            .map(Service::name).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The databases the services of the module use. */
    public List<Database> databasesOf(String module) {
        var services = servicesIn(module);
        return databases.stream().filter(d -> d.usedBy().stream().anyMatch(services::contains)).toList();
    }

    /**
     * A JDBC URL the app uses inside Docker, as Faultline reaches the same database from this machine. A database of
     * that name at another address, such as one the service reaches through a proxy, is the module's database of that
     * name, when it has exactly one; any other URL stays as it is.
     */
    public String hostJdbcUrl(String url, String module) {
        var m = JDBC.matcher(url);
        if (!m.matches()) return url;
        String address = withPort(m.group(1), POSTGRES_PORT);
        String params = m.group(3) == null ? "" : m.group(3);
        for (var d : databases) {
            if (d.address().equals(address) && d.name().equals(m.group(2))) return d.hostUrl() + params;
        }
        var named = databasesOf(module).stream().filter(d -> d.name().equals(m.group(2))).toList();
        return named.size() == 1 ? named.getFirst().hostUrl() + params : url;
    }

    /** The bootstrap servers the app uses inside Docker, as Faultline reaches the broker from this machine; others stay. */
    public String hostBootstrap(String bootstrap) {
        if (kafka == null || kafka.host() == null) return bootstrap;
        boolean inDocker = Arrays.stream(bootstrap.split(",")).map(Environment::withoutScheme).anyMatch(kafka.addresses()::contains);
        return inDocker ? kafka.host() : bootstrap;
    }

    /** The tables of the module's databases with their columns, in lower case, as the plan's rule check wants them. */
    public Map<String, Set<String>> schemaOf(String module) {
        var schema = new TreeMap<String, Set<String>>();
        for (var d : databasesOf(module)) {
            for (var t : d.tables()) {
                schema.computeIfAbsent(t.name().toLowerCase(Locale.ROOT), k -> new TreeSet<>())
                    .addAll(t.columns().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).toList());
            }
        }
        return schema;
    }

    /** Tables that already had rows once the app had started: data the app needs, which a reset must not empty. */
    public Set<String> seededTablesOf(String module) {
        return databasesOf(module).stream().flatMap(d -> d.tables().stream()).filter(t -> t.rows() > 0)
            .map(t -> t.name().toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The module's databases as text for the LLM: each table with its columns and types, and its rows after starting. */
    public String describeSchemaOf(String module) {
        return databasesOf(module).stream().map(d -> "-- database %s on %s (used by %s)\n%s".formatted(d.name(), d.service(),
                String.join(", ", d.usedBy()), d.tables().stream()
                    .map(t -> "table %s (%s) -- %d rows after the app started%s".formatted(t.name(),
                        t.columns().stream().map(c -> c.name() + " " + c.type()).collect(Collectors.joining(", ")), t.rows(), keysOf(t)))
                    .collect(Collectors.joining("\n"))))
            .collect(Collectors.joining("\n\n"));
    }

    /** ", customer_id 1, 2", or the first of them when there are more; empty without keys. */
    private static String keysOf(Table t) {
        if (t.key() == null || t.keys() == null || t.keys().isEmpty()) return "";
        return ", %s %s%s".formatted(t.key(), String.join(", ", t.keys()), t.rows() > t.keys().size() ? " ... (the first " + t.keys().size() + ")" : "");
    }

    static String withPort(String address, int port) {
        return address.contains(":") ? address : address + ":" + port;
    }

    private static String withoutScheme(String address) {
        String a = address.strip();
        int i = a.indexOf("://");
        return i >= 0 ? a.substring(i + 3) : a;
    }
}
