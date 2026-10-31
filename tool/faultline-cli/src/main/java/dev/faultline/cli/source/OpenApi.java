package dev.faultline.cli.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.faultline.cli.source.RepoFiles.TextFile;
import dev.faultline.cli.source.RequestShape.Field;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * An OpenAPI 3 or Swagger 2 spec in the repo, read for the JSON body of each POST, PUT and PATCH operation. References
 * within the file ($ref) are followed and allOf is merged; oneOf, anyOf and references to other files allow anything.
 */
final class OpenApi {
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final List<String> METHODS = List.of("post", "put", "patch");
    private static final int MAX_DEPTH = 6;

    private OpenApi() {}

    /** Each operation's body shape, by "METHOD /path". Empty if the file does not parse. */
    static Map<String, RequestShape> shapes(TextFile spec) {
        JsonNode root;
        try {
            root = YAML.readTree(spec.text());
        } catch (JacksonException e) {
            return Map.of();
        }
        var shapes = new LinkedHashMap<String, RequestShape>();
        for (var path : root.path("paths").properties()) {
            JsonNode item = path.getValue();
            for (String method : METHODS) {
                JsonNode op = item.path(method);
                if (op.isMissingNode()) continue;
                JsonNode schema = bodySchema(root, item, op);
                if (schema == null) continue;
                shapes.put(method.toUpperCase(Locale.ROOT) + " " + path.getKey(), new RequestShape(spec.path(), field(root, "body", schema, 0).fields()));
            }
        }
        return shapes;
    }

    /** OpenAPI 3: the JSON media type of requestBody.content; Swagger 2: the parameter that is in: body. */
    private static JsonNode bodySchema(JsonNode root, JsonNode item, JsonNode op) {
        for (var media : resolve(root, op.path("requestBody")).path("content").properties()) {
            if (media.getKey().contains("json")) return media.getValue().path("schema");
        }
        var params = new ArrayList<JsonNode>();
        op.path("parameters").forEach(params::add);
        item.path("parameters").forEach(params::add);
        for (JsonNode p : params) {
            JsonNode param = resolve(root, p);
            if ("body".equals(param.path("in").asString(""))) return param.path("schema");
        }
        return null;
    }

    private static Field field(JsonNode root, String name, JsonNode node, int depth) {
        JsonNode schema = merged(root, resolve(root, node));
        if (depth > MAX_DEPTH || schema.has("$ref")) return Field.of(name, null);
        String type = type(schema);
        if (type == null && schema.has("properties")) type = "object";
        var values = new ArrayList<String>();
        schema.path("enum").forEach(v -> values.add(v.asString()));
        var required = new LinkedHashSet<String>();
        schema.path("required").forEach(r -> required.add(r.asString()));
        var fields = new ArrayList<Field>();
        for (var p : schema.path("properties").properties()) {
            fields.add(field(root, p.getKey(), p.getValue(), depth + 1).required(required.contains(p.getKey())));
        }
        Field items = schema.has("items") ? field(root, "items", schema.path("items"), depth + 1) : null;
        return new Field(name, type, false, values, number(schema, "minimum", "exclusiveMinimum"), number(schema, "maximum", "exclusiveMaximum"),
            schema.path("format").isString() ? schema.path("format").asString() : null, fields, items);
    }

    /** The schema with its allOf parts merged in: their properties and required fields together. */
    private static JsonNode merged(JsonNode root, JsonNode schema) {
        if (!schema.has("allOf") || !schema.isObject()) return schema;
        ObjectNode out = ((ObjectNode) schema).deepCopy();
        ObjectNode properties = out.has("properties") ? (ObjectNode) out.get("properties") : out.putObject("properties");
        var required = new LinkedHashSet<String>();
        out.path("required").forEach(r -> required.add(r.asString()));
        for (JsonNode part : schema.path("allOf")) {
            JsonNode p = merged(root, resolve(root, part));
            p.path("properties").properties().forEach(e -> properties.set(e.getKey(), e.getValue()));
            p.path("required").forEach(r -> required.add(r.asString()));
            if (!out.has("type") && p.has("type")) out.set("type", p.get("type"));
        }
        var list = out.putArray("required");
        required.forEach(list::add);
        out.remove("allOf");
        return out;
    }

    /** One of the six types; OpenAPI 3.1 may list several, such as [string, null]. oneOf and anyOf allow anything. */
    private static String type(JsonNode schema) {
        if (schema.has("oneOf") || schema.has("anyOf")) return null;
        JsonNode t = schema.path("type");
        if (t.isArray()) {
            for (JsonNode x : t) if (!"null".equals(x.asString()) && RequestShape.TYPES.contains(x.asString())) return x.asString();
            return null;
        }
        return t.isString() && RequestShape.TYPES.contains(t.asString()) ? t.asString() : null;
    }

    /** {@code minimum}, or an OpenAPI 3.1 numeric {@code exclusiveMinimum} (taken as the bound itself). */
    private static Double number(JsonNode schema, String bound, String exclusive) {
        if (schema.path(bound).isNumber()) return schema.path(bound).asDouble();
        if (schema.path(exclusive).isNumber()) return schema.path(exclusive).asDouble();
        return null;
    }

    /** Follows $ref pointers within the file, such as #/components/schemas/Order; one to another file stays as it is. */
    private static JsonNode resolve(JsonNode root, JsonNode node) {
        Set<String> seen = new LinkedHashSet<>();
        while (node.has("$ref") && node.path("$ref").isString()) {
            String ref = node.path("$ref").asString();
            if (!ref.startsWith("#/") || !seen.add(ref)) return node;
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) return JsonNodeFactory.instance.objectNode();
            node = target;
        }
        return node;
    }
}
