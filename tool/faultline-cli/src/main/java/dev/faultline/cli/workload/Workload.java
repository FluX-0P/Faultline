package dev.faultline.cli.workload;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

    /** The value of {@code field} in a JSON request body. */
    public static String idOf(String body, String field) {
        JsonNode json;
        try {
            json = Json.MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the request body is not valid JSON: " + e.getOriginalMessage() + " in " + body);
        }
        JsonNode id = json.path(field);
        if (!id.isValueNode() || id.isNull()) throw new IllegalArgumentException("the request body has no field '" + field + "': " + body);
        return id.asString();
    }
}
