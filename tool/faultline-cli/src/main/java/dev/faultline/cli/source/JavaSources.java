package dev.faultline.cli.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** A method of the app, named Class#method. */
    public record Method(String handler, String file, int line, MethodDeclaration declaration, TypeDeclaration<?> owner) {}

    private record Unit(String path, CompilationUnit cu) {}

    private final List<Unit> units;
    private final List<String> problems;
    private final SpringConfig config;
    private final DefaultPrettyPrinter noComments = new DefaultPrettyPrinter(new DefaultPrinterConfiguration()
        .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_COMMENTS))
        .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_JAVADOC)));

    private JavaSources(List<Unit> units, List<String> problems, SpringConfig config) {
        this.units = units;
        this.problems = problems;
        this.config = config;
    }

    public static JavaSources parse(RepoFiles repo, SpringConfig config) throws IOException {
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25));
        var units = new ArrayList<Unit>();
        var problems = new ArrayList<String>();
        for (Path file : repo.javaSources()) {
            var result = parser.parse(file);
            String path = repo.relative(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                units.add(new Unit(path, result.getResult().get()));
            } else {
                problems.add(path + ": " + result.getProblems().stream().findFirst().map(p -> p.getVerboseMessage()).orElse("cannot parse"));
            }
        }
        return new JavaSources(units, problems, config);
    }

    /** The same code, with annotation placeholders resolved against another configuration. */
    JavaSources withConfig(SpringConfig other) {
        return new JavaSources(units, problems, other);
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

    public List<Method> methods() {
        var methods = new ArrayList<Method>();
        for (var unit : units) {
            for (TypeDeclaration<?> type : unit.cu().findAll(TypeDeclaration.class)) {
                for (var m : type.getMethods()) {
                    methods.add(new Method(type.getNameAsString() + "#" + m.getNameAsString(), unit.path(), line(m), m, type));
                }
            }
        }
        return methods;
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
            String prefix = m.owner().getAnnotationByName("RequestMapping").map(a -> first(values(a, "value", "path"))).orElse("");
            for (AnnotationExpr a : m.declaration().getAnnotations()) {
                String name = a.getName().getIdentifier();
                if (HTTP_MAPPINGS.containsKey(name)) {
                    String httpMethod = name.equals("RequestMapping") ? requestMethod(a) : HTTP_MAPPINGS.get(name);
                    var paths = values(a, "value", "path");
                    for (String path : paths.isEmpty() ? List.of("") : paths) {
                        endpoints.add(Endpoint.http(httpMethod, joinPaths(prefix, path), m.handler(), m.file(), m.line()));
                    }
                } else if (name.equals("KafkaListener")) {
                    var group = values(a, "groupId");
                    endpoints.add(Endpoint.kafka(values(a, "topics"), group.isEmpty() ? null : group.getFirst(), m.handler(), m.file(), m.line()));
                }
            }
        }
        return endpoints;
    }

    /** Methods with @KafkaListener. */
    public List<Method> kafkaListeners() {
        return methods().stream().filter(m -> m.declaration().getAnnotationByName("KafkaListener").isPresent()).toList();
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
            fieldType(m.owner(), field).flatMap(this::type).ifPresent(t -> types.putIfAbsent(t.getNameAsString(), t));
        }
        return List.copyOf(types.values());
    }

    /** The app's own methods this one calls through a field, such as {@code service.process(...)} -> PaymentService#process. */
    public List<Method> calledMethods(Method m) {
        var called = new LinkedHashMap<String, Method>();
        for (var call : m.declaration().findAll(MethodCallExpr.class)) {
            receiverType(m, call).flatMap(this::type).ifPresent(t -> method(t.getNameAsString() + "#" + call.getNameAsString())
                .ifPresent(callee -> called.putIfAbsent(callee.handler(), callee)));
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
            if (own) method(m.owner().getNameAsString() + "#" + call.getNameAsString()).ifPresent(c -> methods.putIfAbsent(c.handler(), c));
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

    /** The topics the method's sends name: string constants, or a field set from a ${property} setting. */
    public List<String> sentTopics(Method m) {
        var topics = new ArrayList<String>();
        for (var call : sends(m)) {
            if (call.getArguments().isEmpty()) continue;
            var topic = strings(call.getArgument(0), 0);
            if (topic.isEmpty()) topic = fieldName(call.getArgument(0)).flatMap(f -> setting(m.owner(), f)).map(this::resolveProperties).stream().toList();
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
                if (value.isPresent() && resolveProperties(value.get()).equals(topic)) {
                    var key = PROPERTY.matcher(value.get());
                    if (key.find()) return Optional.of(key.group(1));
                }
            }
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
            .flatMap(a -> rawValues(a, "value").stream()).filter(v -> PROPERTY.matcher(v).find()).findFirst();
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
            .flatMap(name -> type(name).stream())
            .toList();
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
                sb.append("  ").append(annotations(type.getAnnotations())).append(kind(type)).append(' ').append(type.getNameAsString());
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
    private List<String> values(AnnotationExpr a, String... names) {
        return rawValues(a, names).stream().map(this::resolveProperties).toList();
    }

    /** The same, with ${property:default} placeholders left as they are. */
    private List<String> rawValues(AnnotationExpr a, String... names) {
        var exprs = new ArrayList<Expression>();
        if (a instanceof SingleMemberAnnotationExpr s && List.of(names).contains("value")) exprs.add(s.getMemberValue());
        if (a instanceof NormalAnnotationExpr n) {
            n.getPairs().stream().filter(p -> List.of(names).contains(p.getNameAsString())).forEach(p -> exprs.add(p.getValue()));
        }
        return exprs.stream().flatMap(e -> strings(e, 0).stream()).toList();
    }

    private List<String> strings(Expression e, int depth) {
        if (depth > 5) return List.of();
        if (e.isStringLiteralExpr()) return List.of(e.asStringLiteralExpr().asString());
        if (e.isArrayInitializerExpr()) return e.asArrayInitializerExpr().getValues().stream().flatMap(v -> strings(v, depth + 1).stream()).toList();
        if (e.isNameExpr()) return constant(null, e.asNameExpr().getNameAsString(), depth);
        if (e.isFieldAccessExpr()) {
            String scope = e.asFieldAccessExpr().getScope().toString();
            return constant(scope.substring(scope.lastIndexOf('.') + 1), e.asFieldAccessExpr().getNameAsString(), depth);
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            var left = strings(b.getLeft(), depth + 1);
            var right = strings(b.getRight(), depth + 1);
            if (left.size() == 1 && right.size() == 1) return List.of(left.getFirst() + right.getFirst());
        }
        return List.of();
    }

    private List<String> constant(String typeName, String fieldName, int depth) {
        for (var type : types()) {
            if (typeName != null && !type.getNameAsString().equals(typeName)) continue;
            for (var field : type.getFields()) {
                for (var v : field.getVariables()) {
                    if (v.getNameAsString().equals(fieldName) && v.getInitializer().isPresent()) {
                        return strings(v.getInitializer().get(), depth + 1);
                    }
                }
            }
        }
        return List.of();
    }

    private String resolveProperties(String value) {
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
