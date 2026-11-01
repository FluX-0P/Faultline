package dev.faultline.cli.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import dev.faultline.cli.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a request body must look like: its fields, each with its JSON type, whether it is required, and the values it
 * allows. From the app's OpenAPI spec when it describes the endpoint, else from the handler's @RequestBody type. The
 * LLM gets it as the facts to fill in, and Faultline checks every body the LLM's template makes against it.
 *
 * @param source where it comes from, such as api/openapi.yaml or the type PaymentRequested
 * @param form whether the app binds it from form fields (an HTML form) rather than JSON
 */
public record RequestShape(String source, List<Field> fields, boolean form) {
    public static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "object", "array");
    // orderLine[0] or matrix[1][2]: a name and its indexes
    private static final Pattern INDEXED = Pattern.compile("([^\\[]+)((?:\\[\\d+])+)");

    public RequestShape(String source, List<Field> fields) {
        this(source, fields, false);
    }

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
            // the fields of each item of a list of objects
            if (f.items() != null) describe(f.items().fields(), indent + "  ", sb);
        }
    }

    /**
     * What is wrong with one body, in words the LLM can act on. Empty if it fits. A form's fields may also be written
     * flat, as their form names ({"address.street": "x"} for {"address": {"street": "x"}}), since they are sent the same.
     */
    public List<String> problems(JsonNode body) {
        var problems = new ArrayList<String>();
        if (!body.isObject()) return List.of("the body must be a JSON object");
        check(fields, form ? nested(body) : body, "", problems);
        return problems;
    }

    /** {"a.b": 1, "c[0].d": 2} -> {"a": {"b": 1}, "c": [{"d": 2}]}; other fields stay as they are. */
    static JsonNode nested(JsonNode flat) {
        var root = Json.MAPPER.createObjectNode();
        for (var e : flat.properties()) {
            var parts = new ArrayList<String>();
            for (String part : e.getKey().split("\\.")) {
                var m = INDEXED.matcher(part);
                if (m.matches()) {
                    parts.add(m.group(1));
                    for (String i : m.group(2).substring(1, m.group(2).length() - 1).split("]\\[")) parts.add("[" + i);
                } else {
                    parts.add(part);
                }
            }
            JsonNode at = root;
            for (int i = 0; i < parts.size() - 1; i++) at = child(at, parts.get(i), parts.get(i + 1).startsWith("["));
            put(at, parts.getLast(), e.getValue());
        }
        return root;
    }

    /** The object or array under a name ("[2]" is an index), made when it is not there yet. */
    private static JsonNode child(JsonNode parent, String name, boolean array) {
        JsonNode existing = name.startsWith("[") ? parent.path(Integer.parseInt(name.substring(1))) : parent.path(name);
        if (existing.isObject() || existing.isArray()) return existing;
        JsonNode made = array ? Json.MAPPER.createArrayNode() : Json.MAPPER.createObjectNode();
        put(parent, name, made);
        return made;
    }

    private static void put(JsonNode parent, String name, JsonNode value) {
        if (parent instanceof ObjectNode o) {
            o.set(name, value);
        } else if (parent instanceof ArrayNode a) {
            int i = Integer.parseInt(name.substring(1));
            while (a.size() <= i) a.addNull();
            a.set(i, value);
        }
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
