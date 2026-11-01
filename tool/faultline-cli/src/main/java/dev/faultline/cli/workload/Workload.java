package dev.faultline.cli.workload;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.faultline.cli.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** A run's traffic: {@code count} requests from the plan's template, each carrying the ID that ties it to its effect. */
public final class Workload {
    private Workload() {}

    /**
     * The same seed always gives the same requests. java.util.Random's algorithm is fixed by the Java spec,
     * so this holds on every JDK and every machine.
     */
    public static List<Request> generate(RequestTemplate template, String idField, long seed, int count) {
        var random = new Random(seed);
        var requests = new ArrayList<Request>(count);
        for (int i = 0; i < count; i++) {
            String body = template.render(seed, i, random);
            requests.add(new Request(i, idOf(body, idField), body));
        }
        return requests;
    }

    /** The value of {@code field} in a JSON request body; a.b is field b of object a. */
    public static String idOf(String body, String field) {
        JsonNode json;
        try {
            json = Json.MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the request body is not valid JSON: " + e.getOriginalMessage() + " in " + body);
        }
        return field(json, field).orElseThrow(() -> new IllegalArgumentException("the request body has no field '" + field + "': " + body));
    }

    /** {userId}, not a template's ${seq}. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("(?<!\\$)\\{([^}/]+)}");

    /** The names of a path's variables, userId in /cart/{userId}/checkout. */
    public static List<String> pathVariables(String path) {
        var names = new ArrayList<String>();
        if (path == null) return names;
        var m = PATH_VARIABLE.matcher(path);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /**
     * The path with each {name} filled in from the body's field of that name (a.b for a nested one), as one path
     * segment. One the body has no value for stays in, encoded, so the app answers 404 and the request counts as failed.
     */
    public static String pathOf(String path, String body) {
        JsonNode parsed;
        try {
            parsed = Json.MAPPER.readTree(body);
        } catch (JacksonException e) {
            parsed = Json.MAPPER.createObjectNode();
        }
        JsonNode json = parsed;
        return PATH_VARIABLE.matcher(path).replaceAll(m -> Matcher.quoteReplacement(field(json, m.group(1)).map(Workload::segment).orElse(segment(m.group()))));
    }

    /** A value as one path segment: percent-encoded, a space as %20. */
    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A value of a JSON document by name, a.b for field b of object a; empty if it has none, or it is no plain value. */
    public static Optional<String> field(JsonNode json, String field) {
        JsonNode id = json.path(field);
        if (id.isMissingNode() && field.contains(".")) {
            id = json;
            for (String part : field.split("\\.")) id = id.path(part);
        }
        return !id.isValueNode() || id.isNull() ? Optional.empty() : Optional.of(id.asString());
    }
}
