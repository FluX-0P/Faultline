package dev.faultline.cli.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

/**
 * What a request body must look like: its fields, each with its JSON type, whether it is required, and the values it
 * allows. From the app's OpenAPI spec when it describes the endpoint, else from the handler's @RequestBody type. The
 * LLM gets it as the facts to fill in, and Faultline checks every body the LLM's template makes against it.
 *
 * @param source where it comes from, such as api/openapi.yaml or the type PaymentRequested
 */
public record RequestShape(String source, List<Field> fields) {
    public static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "object", "array");

    /**
     * {@code type}: string, integer, number, boolean, object or array, or null when anything goes. {@code values}: the
     * only values allowed (an enum), else empty. {@code fields}: an object's own (empty: any); {@code items}: an
     * array's (null: any).
     */
    public record Field(String name, String type, boolean required, List<String> values, Double minimum, Double maximum,
                        String format, List<Field> fields, Field items) {
        static Field of(String name, String type) {
            return new Field(name, type, false, List.of(), null, null, null, List.of(), null);
        }

        Field required(boolean required) {
            return new Field(name, type, required, values, minimum, maximum, format, fields, items);
        }

        Field named(String name) {
            return new Field(name, type, required, values, minimum, maximum, format, fields, items);
        }

        String describe() {
            var parts = new ArrayList<String>();
            parts.add(type == null ? "any value" : type + (format == null ? "" : " (" + format + ")"));
            if (required) parts.add("required");
            if (!values.isEmpty()) parts.add("one of " + String.join(", ", values));
            if (minimum != null) parts.add("at least " + number(minimum));
            if (maximum != null) parts.add("at most " + number(maximum));
            if (items != null) parts.add("of " + items.describe());
            return String.join(", ", parts);
        }
    }

    /** One line per field, nested fields indented under their object. */
    public String describe() {
        var sb = new StringBuilder();
        describe(fields, "", sb);
        return sb.toString().stripTrailing();
    }

    private static void describe(List<Field> fields, String indent, StringBuilder sb) {
        for (Field f : fields) {
            sb.append(indent).append("- ").append(f.name()).append(": ").append(f.describe()).append('\n');
            describe(f.fields(), indent + "  ", sb);
        }
    }

    /** What is wrong with one body, in words the LLM can act on. Empty if it fits. */
    public List<String> problems(JsonNode body) {
        var problems = new ArrayList<String>();
        if (!body.isObject()) return List.of("the body must be a JSON object");
        check(fields, body, "", problems);
        return problems;
    }

    private static void check(List<Field> fields, JsonNode object, String prefix, List<String> problems) {
        if (fields.isEmpty()) return;
        var names = fields.stream().map(Field::name).toList();
        for (var e : object.properties()) {
            if (!names.contains(e.getKey())) {
                problems.add("field %s%s is not in the request body (its fields: %s)".formatted(prefix, e.getKey(), String.join(", ", names)));
            }
        }
        for (Field f : fields) {
            JsonNode value = object.get(f.name());
            if (value == null || value.isNull()) {
                if (f.required()) problems.add("field " + prefix + f.name() + " is required");
            } else {
                check(f, value, prefix + f.name(), problems);
            }
        }
    }

    private static void check(Field f, JsonNode value, String name, List<String> problems) {
        if (f.type() == null) return;
        boolean fits = switch (f.type()) {
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> true;
        };
        if (!fits) {
            problems.add("field %s must be %s %s, not %s".formatted(name, f.type().equals("integer") || f.type().equals("array") ? "an" : "a", f.type(), value));
            return;
        }
        if (!f.values().isEmpty() && !f.values().contains(value.asString())) {
            problems.add("field %s must be one of %s, not %s".formatted(name, String.join(", ", f.values()), value));
        }
        if (value.isNumber() && f.minimum() != null && value.asDouble() < f.minimum()) {
            problems.add("field %s must be at least %s, not %s".formatted(name, number(f.minimum()), value));
        }
        if (value.isNumber() && f.maximum() != null && value.asDouble() > f.maximum()) {
            problems.add("field %s must be at most %s, not %s".formatted(name, number(f.maximum()), value));
        }
        if (value.isObject()) check(f.fields(), value, name + ".", problems);
        if (value.isArray() && f.items() != null) {
            for (int i = 0; i < value.size(); i++) check(f.items(), value.get(i), name + "[" + i + "]", problems);
        }
    }

    /** The names of the fields, for a short summary. */
    public String fieldNames() {
        return fields.stream().map(Field::name).collect(Collectors.joining(", "));
    }

    private static String number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }
}
