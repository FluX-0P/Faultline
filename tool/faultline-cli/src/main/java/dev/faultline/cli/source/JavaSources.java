package dev.faultline.cli.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration.ConfigOption;

/**
 * The app's main Java code as JavaParser syntax trees. Finds HTTP endpoints and Kafka listeners, resolves the string
 * constants and ${property} placeholders their annotations use, and prints code without comments for the LLM:
 * it should judge what the code does, not what a comment says it does.
 */
public final class JavaSources {
    private static final Map<String, String> HTTP_MAPPINGS = Map.of("GetMapping", "GET", "PostMapping", "POST",
        "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "ANY");
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");
    /** Types the app sends Kafka messages through. */
    private static final Set<String> PRODUCERS = Set.of("KafkaTemplate", "KafkaOperations", "StreamBridge");
    /** Parameter annotations that bind a handler's parameter from anything but form fields. */
    private static final Set<String> OTHER_BINDINGS = Set.of("RequestBody", "PathVariable", "RequestParam", "RequestHeader", "CookieValue",
        "RequestPart", "RequestAttribute", "SessionAttribute", "MatrixVariable", "AuthenticationPrincipal");

    /**
     * A method of the app, named Class#method, or module:Class#method when another module of the repo has a class of the
     * same name. {@code module} is the path of its module, null for code outside every module (a library of the repo).
     */
    public record Method(String handler, String file, int line, MethodDeclaration declaration, TypeDeclaration<?> owner, String module) {}

    /** {@code module}: the path of the module the file is in, null if none. */
    private record Unit(String path, CompilationUnit cu, String module) {}

    private final List<Unit> units;
    private final List<String> problems;
    /** Each module's configuration and name, by its path. */
    private final Map<String, SpringConfig> configs;
    private final Map<String, String> names;
    /** The repo's OpenAPI specs: endpoints whose controllers are generated at build time are only there. */
    private final List<RepoFiles.TextFile> specs;
    /** Simple names of types that more than one module declares: their methods' handlers carry the module. */
    private final Set<String> clashing;
    private final Map<CompilationUnit, Unit> unitOf = new IdentityHashMap<>();
    private final DefaultPrettyPrinter noComments = new DefaultPrettyPrinter(new DefaultPrinterConfiguration()
        .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_COMMENTS))
        .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_JAVADOC)));

    private JavaSources(List<Unit> units, List<String> problems, Map<String, SpringConfig> configs, Map<String, String> names,
                        List<RepoFiles.TextFile> specs) {
        this.units = units;
        this.problems = problems;
        this.configs = configs;
        this.names = names;
        this.specs = specs;
        units.forEach(u -> unitOf.put(u.cu(), u));
        var declaredIn = new HashMap<String, Set<String>>();
        for (var u : units) {
            for (TypeDeclaration<?> type : u.cu().findAll(TypeDeclaration.class)) {
                declaredIn.computeIfAbsent(type.getNameAsString(), k -> new HashSet<>()).add(moduleName(u));
            }
        }
        this.clashing = declaredIn.entrySet().stream().filter(e -> e.getValue().size() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    /** The code of a folder that is one app, with its configuration. */
    public static JavaSources parse(RepoFiles repo, SpringConfig config) throws IOException {
        return parse(repo, List.of(new App.Module(App.ROOT_MODULE, repo.name(), repo, config)));
    }

    /** The code of a repo; each file belongs to the deepest of the modules whose folder holds it. */
    static JavaSources parse(RepoFiles repo, List<App.Module> modules) throws IOException {
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25));
        var units = new ArrayList<Unit>();
        var problems = new ArrayList<String>();
        for (Path file : repo.javaSources()) {
            var result = parser.parse(file);
            String path = repo.relative(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                String module = modules.stream().map(App.Module::path).filter(m -> m.isEmpty() || path.startsWith(m + "/"))
                    .max(Comparator.comparingInt(String::length)).orElse(null);
                units.add(new Unit(path, result.getResult().get(), module));
            } else {
                problems.add(path + ": " + result.getProblems().stream().findFirst().map(p -> p.getVerboseMessage()).orElse("cannot parse"));
            }
        }
        return new JavaSources(units, problems, configs(modules), names(modules), repo.openApiSpecs());
    }

    /** The same code, with annotation placeholders resolved against the modules' changed configurations. */
    JavaSources withModules(List<App.Module> modules) {
        return new JavaSources(units, problems, configs(modules), names(modules), specs);
    }

    private static Map<String, SpringConfig> configs(List<App.Module> modules) {
        return modules.stream().collect(Collectors.toMap(App.Module::path, App.Module::config));
    }

    private static Map<String, String> names(List<App.Module> modules) {
        return modules.stream().collect(Collectors.toMap(App.Module::path, App.Module::name));
    }

    /** A module's name; code outside every module goes by its top folder, such as common. */
    private String moduleName(Unit u) {
        if (u.module() != null) return names.get(u.module());
        return u.path().contains("/") ? u.path().substring(0, u.path().indexOf('/')) : u.path();
    }

    /** The path of the module a type's file is in, null for the repo's code outside every module. */
    public String moduleOf(TypeDeclaration<?> type) {
        return type.findCompilationUnit().map(unitOf::get).map(Unit::module).orElse(null);
    }

    /** OrderKafkaListener, or shipping:OrderKafkaListener when another module has a type of that name. */
    private String qualified(TypeDeclaration<?> type, Unit u) {
        String name = type.getNameAsString();
        return clashing.contains(name) ? moduleName(u) + ":" + name : name;
    }

    /** Files JavaParser could not read; they are left out. */
    public List<String> problems() {
        return problems;
    }

    public int fileCount() {
        return units.size();
    }

    public List<TypeDeclaration<?>> types() {
        var types = new ArrayList<TypeDeclaration<?>>();
        for (var unit : units) {
            for (TypeDeclaration<?> type : unit.cu().findAll(TypeDeclaration.class)) types.add(type);
        }
        return types;
    }

    public Optional<TypeDeclaration<?>> type(String simpleName) {
        return types().stream().filter(t -> t.getNameAsString().equals(simpleName)).findFirst();
    }

    /** A type as the code of a module sees it: the module's own first, then the repo's code outside every module, then any. */
    public Optional<TypeDeclaration<?>> type(String module, String simpleName) {
        var named = types().stream().filter(t -> t.getNameAsString().equals(simpleName)).toList();
        return named.stream().filter(t -> Objects.equals(moduleOf(t), module)).findFirst()
            .or(() -> named.stream().filter(t -> moduleOf(t) == null).findFirst())
            .or(() -> named.stream().findFirst());
    }

    public List<Method> methods() {
        var methods = new ArrayList<Method>();
        for (var unit : units) {
            for (TypeDeclaration<?> type : unit.cu().findAll(TypeDeclaration.class)) {
                String owner = qualified(type, unit);
                for (var m : type.getMethods()) {
                    methods.add(new Method(owner + "#" + m.getNameAsString(), unit.path(), line(m), m, type, unit.module()));
                }
            }
        }
        return methods;
    }

    /** The first method of this name that the type declares. */
    private Optional<Method> methodIn(TypeDeclaration<?> type, String name) {
        return methods().stream().filter(m -> m.owner() == type && m.declaration().getNameAsString().equals(name)).findFirst();
    }

    public Optional<Method> method(String handler) {
        return methods().stream().filter(m -> m.handler().equals(handler)).findFirst();
    }

    /** Every Class#method in the code, for checking that the LLM only names methods that exist. */
    public Set<String> handlers() {
        return methods().stream().map(Method::handler).collect(Collectors.toCollection(TreeSet::new));
    }

    public List<Endpoint> endpoints() {
        var endpoints = new ArrayList<Endpoint>();
        for (var m : methods()) {
            String module = m.module();
            String prefix = m.owner().getAnnotationByName("RequestMapping").map(a -> first(values(a, module, "value", "path"))).orElse("");
            for (AnnotationExpr a : m.declaration().getAnnotations()) {
                String name = a.getName().getIdentifier();
                // a Feign client's mappings are the calls the app makes to another service, not ways into it
                if (HTTP_MAPPINGS.containsKey(name) && m.owner().getAnnotationByName("FeignClient").isEmpty()) {
                    String httpMethod = name.equals("RequestMapping") ? requestMethod(a) : HTTP_MAPPINGS.get(name);
                    var paths = values(a, module, "value", "path");
                    for (String path : paths.isEmpty() ? List.of("") : paths) {
                        endpoints.add(Endpoint.http(httpMethod, joinPaths(prefix, path), m.handler(), m.file(), m.line()));
                    }
                } else if (name.equals("KafkaListener")) {
                    var group = values(a, module, "groupId");
                    endpoints.add(Endpoint.kafka(values(a, module, "topics"), group.isEmpty() ? null : group.getFirst(), m.handler(), m.file(), m.line()));
                } else if (name.equals("KafkaHandler")) {
                    // a class-level @KafkaListener: its topics and group, with each @KafkaHandler taking one kind of message
                    m.owner().getAnnotationByName("KafkaListener").ifPresent(l -> {
                        var group = values(l, module, "groupId");
                        endpoints.add(Endpoint.kafka(values(l, module, "topics"), group.isEmpty() ? null : group.getFirst(), m.handler(), m.file(), m.line()));
                    });
                }
            }
        }
        endpoints.addAll(specEndpoints(endpoints));
        return endpoints;
    }

    /**
     * The operations of the repo's OpenAPI specs that no annotation in the code maps: controllers that openapi-generator
     * makes at build time, which the app's classes implement. Each goes to the method of the operation's name in a class
     * that implements an interface the code does not declare (the generated one), under that class's @RequestMapping,
     * else the module's openapi.*.base-path (the generated interface's, when it has one); with no such class, its
     * handler is openapi:operationId.
     */
    private List<Endpoint> specEndpoints(List<Endpoint> annotated) {
        var mapped = new HashSet<String>();
        annotated.stream().filter(e -> e.kind() == Endpoint.Kind.HTTP).forEach(e -> mapped.add(e.method() + " " + bareParameters(e.path())));
        var found = new ArrayList<Endpoint>();
        for (var spec : specs) {
            for (var op : OpenApi.operations(spec)) {
                var impl = implementationOf(op.javaName());
                // Spring takes the class's own @RequestMapping over the interface's
                String prefix = impl.map(m -> m.owner().getAnnotationByName("RequestMapping").map(a -> first(values(a, m.module(), "value", "path")))
                    .orElseGet(() -> configs.getOrDefault(m.module(), SpringConfig.EMPTY).find(BASE_PATH).orElse(""))).orElse("");
                String path = joinPaths(prefix, op.path());
                String key = bareParameters(path);
                if (mapped.contains(op.method() + " " + key) || mapped.contains("ANY " + key) || !mapped.add(op.method() + " " + key)) continue;
                found.add(impl.map(m -> Endpoint.fromSpec(op.method(), path, m.handler(), m.file(), m.line(), spec.path()))
                    .orElse(Endpoint.fromSpec(op.method(), path, Endpoint.UNIMPLEMENTED + op.javaName(), spec.path(), op.line(), spec.path())));
            }
        }
        return found;
    }

    private static final Pattern BASE_PATH = Pattern.compile("openapi\\.[\\w-]+\\.base-path");

    /** /orders/{orderId} and /orders/{id} are the same path. */
    private static String bareParameters(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    /**
     * The method that implements a generated operation: one of that name, with a body, in a class that implements an
     * interface the code does not declare; one marked @Override first.
     */
    private Optional<Method> implementationOf(String javaName) {
        return methods().stream()
            .filter(m -> m.declaration().getNameAsString().equals(javaName) && m.declaration().getBody().isPresent())
            .filter(m -> m.owner().isClassOrInterfaceDeclaration() && !m.owner().asClassOrInterfaceDeclaration().isInterface()
                && m.owner().asClassOrInterfaceDeclaration().getImplementedTypes().stream().anyMatch(t -> type(m.module(), t.getNameAsString()).isEmpty()))
            .min(Comparator.comparing(m -> m.declaration().getAnnotationByName("Override").isEmpty()));
    }

    /** Methods with @KafkaListener, and the @KafkaHandler methods of a class with @KafkaListener. */
    public List<Method> kafkaListeners() {
        return methods().stream().filter(m -> m.declaration().getAnnotationByName("KafkaListener").isPresent()
            || m.declaration().getAnnotationByName("KafkaHandler").isPresent() && m.owner().getAnnotationByName("KafkaListener").isPresent()).toList();
    }

    /** What the container passes a listener besides its message. */
    private static final Set<String> NOT_THE_MESSAGE = Set.of("Acknowledgment", "Consumer", "ConsumerRecordMetadata", "MessageHeaders", "Headers");
    /** What a listener's message comes in: the message is the last type argument (ConsumerRecord<String, OrderEvent>). */
    private static final Set<String> MESSAGE_WRAPPERS = Set.of("ConsumerRecord", "ConsumerRecords", "Message", "List", "Collection", "Iterable", "Optional");
    private static final Set<String> BATCHES = Set.of("List", "Collection", "Iterable", "ConsumerRecords");

    /** The listener's parameters that carry its message: neither the container's extras nor a @Header. */
    private static List<Parameter> messageParameters(Method listener) {
        return listener.declaration().getParameters().stream()
            .filter(p -> !NOT_THE_MESSAGE.contains(typeName(p)))
            .filter(p -> p.getAnnotationByName("Header").isEmpty() && p.getAnnotationByName("Headers").isEmpty())
            .toList();
    }

    /** Whether a Kafka listener takes a whole poll at once: a List of messages or records, ConsumerRecords, or batch = "true". */
    public boolean isBatchListener(Method listener) {
        return messageParameters(listener).stream().anyMatch(p -> BATCHES.contains(typeName(p)))
            || listener.declaration().getAnnotationByName("KafkaListener").map(a -> rawValues(a, listener.module(), "batch").contains("true")).orElse(false);
    }

    /**
     * The app's own types of the message a Kafka listener takes, however the container hands it over: as the payload
     * (with or without @Payload), in a ConsumerRecord or a Message, or a batch of them, such as
     * {@code List<ConsumerRecord<String, OrderEvent>>} -> OrderEvent.
     */
    public List<TypeDeclaration<?>> messageTypes(Method listener) {
        var types = new LinkedHashMap<String, TypeDeclaration<?>>();
        for (var p : messageParameters(listener)) {
            Type t = p.getType();
            for (int depth = 0; depth < 4 && t.isClassOrInterfaceType() && MESSAGE_WRAPPERS.contains(t.asClassOrInterfaceType().getNameAsString()); depth++) {
                var args = t.asClassOrInterfaceType().getTypeArguments().orElse(null);
                if (args == null || args.isEmpty()) break;
                t = args.get(args.size() - 1);
            }
            if (t.isClassOrInterfaceType()) type(listener.module(), t.asClassOrInterfaceType().getNameAsString()).ifPresent(x -> types.putIfAbsent(x.getNameAsString(), x));
        }
        return List.copyOf(types.values());
    }

    /** Whether a listener replies with what it returns: @SendTo, which Spring Kafka sends once the method has returned. */
    public boolean replies(Method m) {
        return m.declaration().getAnnotationByName("SendTo").isPresent();
    }

    /** The topics a listener's @SendTo names; empty when it names none, so each message's reply-topic header decides. */
    public List<String> replyTopics(Method m) {
        return m.declaration().getAnnotationByName("SendTo").map(a -> values(a, m.module(), "value")).orElse(List.of());
    }

    /**
     * The app's methods a method's work goes to: those it calls through a field, from its own body (lambdas included)
     * and from the methods of its own class it calls, such as a batch listener's method for one record.
     */
    public List<Method> workOf(Method m) {
        var work = new LinkedHashMap<String, Method>();
        for (var x : withOwnCallees(m)) calledMethods(x).forEach(c -> work.putIfAbsent(c.handler(), c));
        return List.copyOf(work.values());
    }

    /** @KafkaListener methods that take an Acknowledgment and call acknowledge(): pattern P1's candidates. */
    public List<Method> kafkaListenersThatAcknowledge() {
        return kafkaListeners().stream()
            .filter(m -> m.declaration().getParameters().stream().anyMatch(p -> typeName(p).equals("Acknowledgment")))
            .filter(m -> !m.declaration().findAll(MethodCallExpr.class, c -> c.getNameAsString().equals("acknowledge")).isEmpty())
            .toList();
    }

    /** The app's own classes this method calls through a field, such as {@code service.process(...)}. */
    public List<TypeDeclaration<?>> calledTypes(Method m) {
        var types = new LinkedHashMap<String, TypeDeclaration<?>>();
        for (var call : m.declaration().findAll(MethodCallExpr.class)) {
            var scope = call.getScope().orElse(null);
            String field = scope == null ? null
                : scope.isNameExpr() ? scope.asNameExpr().getNameAsString()
                : scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr() ? scope.asFieldAccessExpr().getNameAsString()
                : null;
            if (field == null) continue;
            fieldType(m.owner(), field).flatMap(t -> type(m.module(), t)).ifPresent(t -> types.putIfAbsent(t.getNameAsString(), t));
        }
        return List.copyOf(types.values());
    }

    /** The app's own methods this one calls through a field, such as {@code service.process(...)} -> PaymentService#process. */
    public List<Method> calledMethods(Method m) {
        var called = new LinkedHashMap<String, Method>();
        for (var call : m.declaration().findAll(MethodCallExpr.class)) {
            receiverType(m, call).flatMap(t -> type(m.module(), t)).flatMap(t -> methodIn(t, call.getNameAsString()))
                .ifPresent(callee -> called.putIfAbsent(callee.handler(), callee));
        }
        return List.copyOf(called.values());
    }

    /** The app's methods that call this one through a field: who reaches it. */
    public List<Method> callers(Method m) {
        return methods().stream().filter(x -> calledMethods(x).stream().anyMatch(c -> c.handler().equals(m.handler()))).toList();
    }

    /** The method and the methods of its own class it calls (one level deep): where its work really happens. */
    public List<Method> withOwnCallees(Method m) {
        var methods = new LinkedHashMap<String, Method>();
        methods.put(m.handler(), m);
        for (var call : m.declaration().findAll(MethodCallExpr.class)) {
            boolean own = call.getScope().isEmpty() || call.getScope().get().isThisExpr();
            if (own) methodIn(m.owner(), call.getNameAsString()).ifPresent(c -> methods.putIfAbsent(c.handler(), c));
        }
        return List.copyOf(methods.values());
    }

    /**
     * The simple type name of the field a call goes through, following a chain to its start:
     * {@code http.post().uri("/x").retrieve()} -> the type of field {@code http}. Empty if the call is not on a field.
     */
    public Optional<String> receiverType(Method m, MethodCallExpr call) {
        Expression scope = call.getScope().orElse(null);
        while (scope != null && scope.isMethodCallExpr()) scope = scope.asMethodCallExpr().getScope().orElse(null);
        if (scope == null) return Optional.empty();
        String field = scope.isNameExpr() ? scope.asNameExpr().getNameAsString()
            : scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr() ? scope.asFieldAccessExpr().getNameAsString()
            : null;
        return field == null ? Optional.empty() : fieldType(m.owner(), field);
    }

    /** The method's send calls on a Kafka producer field, such as {@code kafka.send(topic, key, payload)}. */
    public List<MethodCallExpr> sends(Method m) {
        return m.declaration().findAll(MethodCallExpr.class, c -> c.getNameAsString().startsWith("send")
            && receiverType(m, c).filter(PRODUCERS::contains).isPresent());
    }

    /**
     * The topics the method's sends name: string constants, or a field set from a ${property} setting; and the topics
     * its @SendTo replies go to.
     */
    public List<String> sentTopics(Method m) {
        var topics = new ArrayList<String>(replyTopics(m));
        for (var call : sends(m)) {
            if (call.getArguments().isEmpty()) continue;
            var topic = strings(call.getArgument(0), m.module(), 0);
            if (topic.isEmpty()) {
                topic = fieldName(call.getArgument(0)).flatMap(f -> setting(m.owner(), f)).map(v -> resolveProperties(m.module(), v)).stream().toList();
            }
            topic.stream().filter(t -> !topics.contains(t)).forEach(topics::add);
        }
        return topics;
    }

    /**
     * The setting that names the topic the app sends to, when it names {@code topic} now: a field set from
     * {@code @Value("${key:default}")}, such as the API's inbound topic. Changing it can put a relay in front of the listener.
     */
    public Optional<String> topicSetting(String topic) {
        for (var m : methods()) {
            for (var call : sends(m)) {
                if (call.getArguments().isEmpty()) continue;
                var value = fieldName(call.getArgument(0)).flatMap(f -> setting(m.owner(), f));
                if (value.isPresent() && resolveProperties(m.module(), value.get()).equals(topic)) {
                    var key = PROPERTY.matcher(value.get());
                    if (key.find()) return Optional.of(key.group(1));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The headers a handler cannot do without: its {@code @RequestHeader} parameters that are required and have no
     * default, by the name they read (the parameter's own when the annotation names none).
     */
    public List<String> requiredHeaders(Method handler) {
        var headers = new ArrayList<String>();
        for (var p : handler.declaration().getParameters()) {
            var a = p.getAnnotationByName("RequestHeader");
            if (a.isEmpty()) continue;
            boolean optional = a.get() instanceof NormalAnnotationExpr n && n.getPairs().stream()
                .anyMatch(pair -> pair.getNameAsString().equals("required") && pair.getValue().toString().equals("false"));
            if (optional || !rawValues(a.get(), handler.module(), "defaultValue").isEmpty()) continue;
            // a Map or HttpHeaders parameter takes them all
            String type = p.getTypeAsString();
            if (type.startsWith("Map") || type.endsWith("HttpHeaders") || type.startsWith("MultiValueMap")) continue;
            var names = rawValues(a.get(), handler.module(), "value", "name");
            headers.add(names.isEmpty() || names.getFirst().isBlank() ? p.getNameAsString() : names.getFirst());
        }
        return headers;
    }

    /**
     * The setting a listener's topics come from, when it names {@code topic} now: {@code @KafkaListener(topics =
     * "${app.kafka.topic}")}. Changing it moves the listener, which puts a relay in front of it whoever sends.
     */
    public Optional<String> listenerTopicSetting(Method listener, String topic) {
        var annotation = listener.declaration().getAnnotationByName("KafkaListener")
            .or(() -> listener.declaration().getAnnotationByName("KafkaHandler").flatMap(h -> listener.owner().getAnnotationByName("KafkaListener")));
        if (annotation.isEmpty()) return Optional.empty();
        for (String raw : rawValues(annotation.get(), listener.module(), "topics")) {
            var key = PROPERTY.matcher(raw);
            if (key.matches() && resolveProperties(listener.module(), raw).equals(topic)) return Optional.of(key.group(1));
        }
        return Optional.empty();
    }

    /** The raw @Value text a field is set from, on the field or on the constructor parameter of the same name. */
    private Optional<String> setting(TypeDeclaration<?> owner, String field) {
        var annotations = new ArrayList<AnnotationExpr>();
        for (var f : owner.getFields()) {
            if (f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(field))) annotations.addAll(f.getAnnotations());
        }
        for (var c : owner.getConstructors()) c.getParameterByName(field).ifPresent(p -> annotations.addAll(p.getAnnotations()));
        return annotations.stream().filter(a -> a.getName().getIdentifier().equals("Value"))
            .flatMap(a -> rawValues(a, moduleOf(owner), "value").stream()).filter(v -> PROPERTY.matcher(v).find()).findFirst();
    }

    private static Optional<String> fieldName(Expression e) {
        if (e.isNameExpr()) return Optional.of(e.asNameExpr().getNameAsString());
        if (e.isFieldAccessExpr() && e.asFieldAccessExpr().getScope().isThisExpr()) return Optional.of(e.asFieldAccessExpr().getNameAsString());
        return Optional.empty();
    }

    /** Every string literal and text block in the method, such as its SQL. */
    public static List<String> strings(Method m) {
        var strings = new ArrayList<String>();
        for (var s : m.declaration().findAll(StringLiteralExpr.class)) strings.add(s.asString());
        for (var t : m.declaration().findAll(TextBlockLiteralExpr.class)) strings.add(t.stripIndent());
        return strings;
    }

    /** The types of a handler's @RequestBody parameters, when they are the app's own classes or records. */
    public List<TypeDeclaration<?>> requestBodyTypes(Method m) {
        return m.declaration().getParameters().stream()
            .filter(p -> p.getAnnotationByName("RequestBody").isPresent())
            .map(JavaSources::typeName)
            .flatMap(name -> type(m.module(), name).stream())
            .toList();
    }

    /**
     * The types of a handler's parameters that Spring binds from form fields: the app's own classes and records with no
     * annotation that binds them otherwise (@RequestBody, @PathVariable, @RequestParam ...), such as {@code post(Order order)}.
     */
    public List<TypeDeclaration<?>> formBodyTypes(Method m) {
        return m.declaration().getParameters().stream()
            .filter(p -> p.getAnnotations().stream().noneMatch(a -> OTHER_BINDINGS.contains(a.getName().getIdentifier())))
            .map(JavaSources::typeName)
            .flatMap(name -> type(m.module(), name).stream())
            .filter(t -> !t.isEnumDeclaration() && !(t.isClassOrInterfaceDeclaration() && t.asClassOrInterfaceDeclaration().isInterface()))
            .toList();
    }

    /**
     * The app's types that a Formatter or a Converter from String of the module reads from one text value, such as a
     * Customer from its ID, by type name, with the class that reads it.
     */
    public Map<String, String> textBoundTypes(String module) {
        var bound = new LinkedHashMap<String, String>();
        for (var type : types()) {
            if (!Objects.equals(moduleOf(type), module) || !type.isClassOrInterfaceDeclaration()) continue;
            for (var parent : type.asClassOrInterfaceDeclaration().getImplementedTypes()) {
                var args = parent.getTypeArguments().orElse(null);
                if (args == null || args.isEmpty()) continue;
                String name = parent.getNameAsString();
                if (name.equals("Formatter")) bound.putIfAbsent(simpleName(args.get(0).asString()), type.getNameAsString());
                if (name.equals("Converter") && args.size() == 2 && simpleName(args.get(0).asString()).equals("String")) {
                    bound.putIfAbsent(simpleName(args.get(1).asString()), type.getNameAsString());
                }
            }
        }
        return bound;
    }

    /** The code of a type, without comments. */
    public String source(TypeDeclaration<?> type) {
        return noComments.print(type);
    }

    /**
     * One line per type, field, constructor and method, with annotations: enough to see what the app has without
     * reading every method body.
     */
    public String outline() {
        var sb = new StringBuilder();
        for (var unit : units) {
            sb.append(unit.path()).append('\n');
            for (TypeDeclaration<?> type : unit.cu().findAll(TypeDeclaration.class)) {
                sb.append("  ").append(annotations(type.getAnnotations())).append(kind(type)).append(' ').append(qualified(type, unit));
                if (type instanceof RecordDeclaration r) {
                    sb.append(r.getParameters().stream().map(this::print).collect(Collectors.joining(", ", "(", ")")));
                }
                sb.append('\n');
                for (FieldDeclaration f : type.getFields()) {
                    sb.append("    ").append(annotations(f.getAnnotations())).append(f.getModifiers().stream().map(mod -> mod.getKeyword().asString() + " ").collect(Collectors.joining()))
                        .append(f.getElementType()).append(' ')
                        .append(f.getVariables().stream().map(v -> v.getNameAsString()).collect(Collectors.joining(", "))).append('\n');
                }
                for (ConstructorDeclaration c : type.getConstructors()) {
                    sb.append("    ").append(annotations(c.getAnnotations())).append(c.getDeclarationAsString(false, false, true)).append('\n');
                }
                for (MethodDeclaration m : type.getMethods()) {
                    sb.append("    ").append(annotations(m.getAnnotations())).append(m.getDeclarationAsString(false, false, true)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String annotations(List<AnnotationExpr> annotations) {
        return annotations.stream().map(a -> print(a) + " ").collect(Collectors.joining());
    }

    private String print(Node node) {
        return noComments.print(node).replaceAll("\\s+", " ").strip();
    }

    private static String kind(TypeDeclaration<?> type) {
        if (type.isRecordDeclaration()) return "record";
        if (type.isEnumDeclaration()) return "enum";
        if (type.isAnnotationDeclaration()) return "@interface";
        return type.isClassOrInterfaceDeclaration() && type.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
    }

    /** The string values of an annotation attribute, with constants and ${property:default} placeholders resolved. */
    private List<String> values(AnnotationExpr a, String module, String... names) {
        return rawValues(a, module, names).stream().map(v -> resolveProperties(module, v)).toList();
    }

    /** The same, with ${property:default} placeholders left as they are. */
    private List<String> rawValues(AnnotationExpr a, String module, String... names) {
        var exprs = new ArrayList<Expression>();
        if (a instanceof SingleMemberAnnotationExpr s && List.of(names).contains("value")) exprs.add(s.getMemberValue());
        if (a instanceof NormalAnnotationExpr n) {
            n.getPairs().stream().filter(p -> List.of(names).contains(p.getNameAsString())).forEach(p -> exprs.add(p.getValue()));
        }
        return exprs.stream().flatMap(e -> strings(e, module, 0).stream()).toList();
    }

    private List<String> strings(Expression e, String module, int depth) {
        if (depth > 5) return List.of();
        if (e.isStringLiteralExpr()) return List.of(e.asStringLiteralExpr().asString());
        if (e.isArrayInitializerExpr()) return e.asArrayInitializerExpr().getValues().stream().flatMap(v -> strings(v, module, depth + 1).stream()).toList();
        if (e.isNameExpr()) return constant(null, e.asNameExpr().getNameAsString(), module, depth);
        if (e.isFieldAccessExpr()) {
            String scope = e.asFieldAccessExpr().getScope().toString();
            return constant(scope.substring(scope.lastIndexOf('.') + 1), e.asFieldAccessExpr().getNameAsString(), module, depth);
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            var left = strings(b.getLeft(), module, depth + 1);
            var right = strings(b.getRight(), module, depth + 1);
            if (left.size() == 1 && right.size() == 1) return List.of(left.getFirst() + right.getFirst());
        }
        return List.of();
    }

    /** A string constant, looked up as the module's code sees it: in its own types first. */
    private List<String> constant(String typeName, String fieldName, String module, int depth) {
        var types = new ArrayList<>(types());
        types.sort(Comparator.comparing((TypeDeclaration<?> t) -> !Objects.equals(moduleOf(t), module)).thenComparing(t -> moduleOf(t) != null));
        for (var type : types) {
            if (typeName != null && !type.getNameAsString().equals(typeName)) continue;
            for (var field : type.getFields()) {
                for (var v : field.getVariables()) {
                    if (v.getNameAsString().equals(fieldName) && v.getInitializer().isPresent()) {
                        return strings(v.getInitializer().get(), module, depth + 1);
                    }
                }
            }
        }
        return List.of();
    }

    /** ${property:default} placeholders, resolved by the module's configuration; code outside every module has none. */
    private String resolveProperties(String module, String value) {
        var config = module == null ? SpringConfig.EMPTY : configs.getOrDefault(module, SpringConfig.EMPTY);
        return PROPERTY.matcher(value).replaceAll(m -> Matcher.quoteReplacement(
            config.get(m.group(1)).orElse(m.group(2) != null ? m.group(2) : m.group())));
    }

    private String requestMethod(AnnotationExpr a) {
        if (a instanceof NormalAnnotationExpr n) {
            for (var pair : n.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    String text = pair.getValue().toString().replaceAll("[{}\\s]", "");
                    return text.substring(text.lastIndexOf('.') + 1).split(",")[0];
                }
            }
        }
        return "ANY";
    }

    private static Optional<String> fieldType(TypeDeclaration<?> owner, String field) {
        for (var f : owner.getFields()) {
            for (var v : f.getVariables()) {
                if (v.getNameAsString().equals(field)) return Optional.of(simpleName(v.getType().asString()));
            }
        }
        return Optional.empty();
    }

    static String typeName(Parameter p) {
        return simpleName(p.getType().asString());
    }

    /** org.example.Foo<Bar> -> Foo */
    private static String simpleName(String type) {
        String raw = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        return raw.substring(raw.lastIndexOf('.') + 1);
    }

    static String joinPaths(String prefix, String path) {
        String joined = ("/" + prefix + "/" + path).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "" : values.getFirst();
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }
}
