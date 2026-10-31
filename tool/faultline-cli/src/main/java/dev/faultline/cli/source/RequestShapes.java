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

    private RequestShapes() {}

    public static Map<String, RequestShape> of(App app) {
        var shapes = new LinkedHashMap<String, RequestShape>();
        for (var e : app.code().endpoints()) {
            if (e.kind() != Endpoint.Kind.HTTP) continue;
            app.code().method(e.handler()).flatMap(m -> app.code().requestBodyTypes(m).stream().findFirst())
                .ifPresent(type -> shapes.put(e.name(), new RequestShape(type.getNameAsString(), fields(app.code(), type, 0))));
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

    /** The JSON fields of an app type: a record's components, or a class's fields that are not static. */
    static List<Field> fields(JavaSources code, TypeDeclaration<?> type, int depth) {
        var fields = new ArrayList<Field>();
        if (type.isRecordDeclaration()) {
            for (var p : type.asRecordDeclaration().getParameters()) fields.add(field(code, p.getNameAsString(), p.getType(), p.getAnnotations(), depth));
        } else {
            for (FieldDeclaration f : type.getFields()) {
                if (f.isStatic()) continue;
                for (var v : f.getVariables()) fields.add(field(code, v.getNameAsString(), v.getType(), f.getAnnotations(), depth));
            }
        }
        return fields;
    }

    private static Field field(JavaSources code, String name, Type type, NodeList<AnnotationExpr> annotations, int depth) {
        Field f = typed(code, name, type, depth);
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
    private static Field typed(JavaSources code, String name, Type type, int depth) {
        if (type.isArrayType()) return withItems(Field.of(name, "array"), typed(code, "items", type.asArrayType().getComponentType(), depth));
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
            return withItems(Field.of(name, "array"), args.isEmpty() ? null : typed(code, "items", args.getFirst().orElseThrow(), depth));
        }
        if (simple.equals("Optional")) {
            var args = c.getTypeArguments().orElse(new NodeList<>());
            return args.isEmpty() ? Field.of(name, null) : typed(code, name, args.getFirst().orElseThrow(), depth);
        }
        var own = code.type(simple);
        if (own.isPresent()) {
            var t = own.get();
            if (t.isEnumDeclaration()) {
                var values = t.asEnumDeclaration().getEntries().stream().map(en -> en.getNameAsString()).toList();
                return new Field(name, "string", false, values, null, null, null, List.of(), null);
            }
            if (depth < MAX_DEPTH) return new Field(name, "object", false, List.of(), null, null, null, fields(code, t, depth + 1), null);
            return Field.of(name, "object");
        }
        return Field.of(name, null);
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
