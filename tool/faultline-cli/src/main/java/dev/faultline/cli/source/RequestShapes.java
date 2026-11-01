package dev.faultline.cli.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import dev.faultline.cli.source.RequestShape.Field;

/**
 * Each HTTP endpoint's request body, by "METHOD /path": from the app's OpenAPI spec when it describes the endpoint,
 * else from the fields of the handler's @RequestBody type and their validation annotations (@NotNull, @Min ...).
 */
public final class RequestShapes {
    private static final int MAX_DEPTH = 4;
    private static final Set<String> INTEGERS = Set.of("int", "long", "short", "byte", "Integer", "Long", "Short", "Byte", "BigInteger");
    private static final Set<String> NUMBERS = Set.of("double", "float", "Double", "Float", "BigDecimal");
    private static final Set<String> STRINGS = Set.of("String", "CharSequence", "char", "Character");
    private static final Map<String, String> FORMATS = Map.of("UUID", "uuid", "LocalDate", "date", "LocalDateTime", "date-time",
        "Instant", "date-time", "OffsetDateTime", "date-time", "ZonedDateTime", "date-time", "LocalTime", "time", "Duration", "duration");
    private static final Set<String> ARRAYS = Set.of("List", "Set", "Collection", "Iterable", "SortedSet", "LinkedList", "ArrayList");
    private static final Set<String> REQUIRED = Set.of("NotNull", "NotBlank", "NotEmpty");

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "ANY");

    /**
     * How the types of one module are bound: {@code text}, for form fields, the types a Formatter of the module reads
     * from one text value (by name, with the Formatter); empty for JSON.
     */
    record Binding(JavaSources code, String module, Map<String, String> text) {}

    private RequestShapes() {}

    /**
     * Each endpoint's request body: its @RequestBody type, else for a POST, PUT or PATCH handler a parameter bound from
     * form fields (an HTML form), else the OpenAPI spec's.
     */
    public static Map<String, RequestShape> of(App app) {
        var shapes = new LinkedHashMap<String, RequestShape>();
        var code = app.code();
        for (var e : code.endpoints()) {
            if (e.kind() != Endpoint.Kind.HTTP) continue;
            var handler = code.method(e.handler());
            if (handler.isEmpty()) continue;
            var m = handler.get();
            var json = code.requestBodyTypes(m).stream().findFirst();
            if (json.isPresent()) {
                shapes.put(e.name(), new RequestShape(json.get().getNameAsString(), fields(new Binding(code, m.module(), Map.of()), json.get(), 0)));
            } else if (BODY_METHODS.contains(e.method())) {
                code.formBodyTypes(m).stream().findFirst().ifPresent(type -> shapes.put(e.name(), new RequestShape("the form fields of " + type.getNameAsString(),
                    known(fields(new Binding(code, m.module(), code.textBoundTypes(m.module())), type, 0)), true)));
            }
        }
        for (var spec : app.files().openApiSpecs()) {
            OpenApi.shapes(spec).forEach((endpoint, shape) -> {
                // an ANY mapping in the code (@RequestMapping without a method) takes the spec's POST, PUT or PATCH
                String any = "ANY " + endpoint.substring(endpoint.indexOf(' ') + 1);
                shapes.put(shapes.containsKey(any) ? any : endpoint, shape);
            });
        }
        return shapes;
    }

    /**
     * The JSON fields of an app type: a record's components, or a class's fields that are not static. An ID the database
     * makes (@GeneratedValue) is left out: a client does not send it.
     */
    static List<Field> fields(Binding b, TypeDeclaration<?> type, int depth) {
        var fields = new ArrayList<Field>();
        if (type.isRecordDeclaration()) {
            for (var p : type.asRecordDeclaration().getParameters()) fields.add(field(b, p.getNameAsString(), p.getType(), p.getAnnotations(), depth));
        } else {
            for (FieldDeclaration f : type.getFields()) {
                if (f.isStatic() || f.getAnnotationByName("GeneratedValue").isPresent()) continue;
                for (var v : f.getVariables()) fields.add(field(b, v.getNameAsString(), v.getType(), f.getAnnotations(), depth));
            }
        }
        return fields;
    }

    private static Field field(Binding b, String name, Type type, NodeList<AnnotationExpr> annotations, int depth) {
        Field f = typed(b, name, type, depth);
        for (var a : annotations) {
            String n = a.getName().getIdentifier();
            Optional<String> value = value(a);
            if (n.equals("JsonProperty") && value.isPresent() && !value.get().isEmpty()) f = f.named(value.get());
            if (REQUIRED.contains(n)) f = f.required(true);
            // if/else, not ?: : a conditional mixing 1.0 and a null Double unboxes the null
            Double min = null;
            Double max = null;
            if (n.equals("Positive")) min = "integer".equals(f.type()) ? Double.valueOf(1) : Double.valueOf(0);
            else if (n.equals("PositiveOrZero")) min = Double.valueOf(0);
            else if (n.equals("Min") || n.equals("DecimalMin")) min = value.map(RequestShapes::number).orElse(null);
            else if (n.equals("Max") || n.equals("DecimalMax")) max = value.map(RequestShapes::number).orElse(null);
            String format = n.equals("Email") ? "email" : f.format();
            f = new Field(f.name(), f.type(), f.required(), f.values(), min != null ? min : f.minimum(), max != null ? max : f.maximum(), format, f.fields(), f.items());
        }
        return f;
    }

    /** The JSON type a Java type is written as, with an app enum's values and an app type's own fields. */
    private static Field typed(Binding b, String name, Type type, int depth) {
        if (type.isArrayType()) return withItems(Field.of(name, "array"), typed(b, "items", type.asArrayType().getComponentType(), depth));
        if (type.isPrimitiveType()) {
            String p = type.asString();
            // a primitive cannot be left out: Jackson would write 0 or false, so a real client sends it
            return Field.of(name, p.equals("boolean") ? "boolean" : INTEGERS.contains(p) ? "integer" : NUMBERS.contains(p) ? "number" : "string").required(true);
        }
        if (!type.isClassOrInterfaceType()) return Field.of(name, null);
        ClassOrInterfaceType c = type.asClassOrInterfaceType();
        String simple = c.getNameAsString();
        if (STRINGS.contains(simple)) return Field.of(name, "string");
        if (FORMATS.containsKey(simple)) return new Field(name, "string", false, List.of(), null, null, FORMATS.get(simple), List.of(), null);
        if (INTEGERS.contains(simple)) return Field.of(name, "integer");
        if (NUMBERS.contains(simple)) return Field.of(name, "number");
        if (simple.equals("Boolean")) return Field.of(name, "boolean");
        if (simple.equals("Map")) return Field.of(name, "object");
        if (ARRAYS.contains(simple)) {
            var args = c.getTypeArguments().orElse(new NodeList<>());
            return withItems(Field.of(name, "array"), args.isEmpty() ? null : typed(b, "items", args.getFirst().orElseThrow(), depth));
        }
        if (simple.equals("Optional")) {
            var args = c.getTypeArguments().orElse(new NodeList<>());
            return args.isEmpty() ? Field.of(name, null) : typed(b, name, args.getFirst().orElseThrow(), depth);
        }
        // a form field the app reads with a Formatter, such as a customer by its ID
        if (b.text().containsKey(simple)) {
            return new Field(name, "string", false, List.of(), null, null, "one text value that " + b.text().get(simple) + " reads", List.of(), null);
        }
        var own = b.code().type(b.module(), simple);
        if (own.isPresent()) {
            var t = own.get();
            if (t.isEnumDeclaration()) {
                var values = t.asEnumDeclaration().getEntries().stream().map(en -> en.getNameAsString()).toList();
                return new Field(name, "string", false, values, null, null, null, List.of(), null);
            }
            if (depth < MAX_DEPTH) return new Field(name, "object", false, List.of(), null, null, null, fields(b, t, depth + 1), null);
            return Field.of(name, "object");
        }
        return Field.of(name, null);
    }

    /** Wrappers a handler returns its JSON answer in. */
    private static final Set<String> WRAPPERS = Set.of("ResponseEntity", "HttpEntity", "Optional", "Mono", "CompletableFuture", "CompletionStage");

    /**
     * The fields of each endpoint's JSON answer, by "METHOD /path", when its handler returns one of the app's own types
     * (in a ResponseEntity, Optional ...): a nested object's as a.b. An endpoint that answers with anything else is left out.
     */
    public static Map<String, List<String>> answers(App app) {
        var answers = new LinkedHashMap<String, List<String>>();
        var code = app.code();
        for (var e : code.endpoints()) {
            if (e.kind() != Endpoint.Kind.HTTP) continue;
            code.method(e.handler()).ifPresent(m -> {
                Type type = m.declaration().getType();
                while (type.isClassOrInterfaceType() && WRAPPERS.contains(type.asClassOrInterfaceType().getNameAsString())) {
                    var args = type.asClassOrInterfaceType().getTypeArguments().orElse(new NodeList<>());
                    if (args.isEmpty()) return;
                    type = args.getFirst().orElseThrow();
                }
                if (!type.isClassOrInterfaceType()) return;
                code.type(m.module(), type.asClassOrInterfaceType().getNameAsString()).filter(t -> !t.isEnumDeclaration()).ifPresent(t -> {
                    var names = new ArrayList<String>();
                    names(fields(new Binding(code, m.module(), Map.of()), t, 0), "", names);
                    answers.put(e.name(), names);
                });
            });
        }
        return answers;
    }

    private static void names(List<Field> fields, String prefix, List<String> into) {
        for (var f : fields) {
            into.add(prefix + f.name());
            names(f.fields(), prefix + f.name() + ".", into);
        }
    }

    /**
     * A form's fields without those of a type Faultline cannot tell how to write as text, such as a Date: Spring binds
     * one only from the format the app declares, and a form leaves a field it does not send empty.
     */
    private static List<Field> known(List<Field> fields) {
        var known = new ArrayList<Field>();
        for (var f : fields) {
            if (f.type() == null) continue;
            var items = f.items() == null ? null
                : new Field(f.items().name(), f.items().type(), f.items().required(), f.items().values(), f.items().minimum(), f.items().maximum(),
                    f.items().format(), known(f.items().fields()), f.items().items());
            known.add(new Field(f.name(), f.type(), f.required(), f.values(), f.minimum(), f.maximum(), f.format(), known(f.fields()), items));
        }
        return known;
    }

    private static Field withItems(Field f, Field items) {
        return new Field(f.name(), f.type(), f.required(), f.values(), f.minimum(), f.maximum(), f.format(), f.fields(), items);
    }

    /** An annotation's value attribute as text, such as 1 in @Min(1) or "0.01" in @DecimalMin("0.01"). */
    private static Optional<String> value(AnnotationExpr a) {
        Expression e = null;
        if (a instanceof SingleMemberAnnotationExpr s) e = s.getMemberValue();
        if (a instanceof NormalAnnotationExpr n) {
            e = n.getPairs().stream().filter(p -> p.getNameAsString().equals("value")).map(p -> p.getValue()).findFirst().orElse(null);
        }
        if (e == null) return Optional.empty();
        if (e.isStringLiteralExpr()) return Optional.of(e.asStringLiteralExpr().asString());
        return Optional.of(e.toString().replaceAll("[lLdDfF_]", ""));
    }

    private static Double number(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
