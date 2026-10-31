package dev.faultline.cli.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import dev.faultline.cli.Json;
import tools.jackson.databind.JsonNode;

/** A JSON schema an LLM reply must follow, from src/main/resources/schemas/{name}.json. */
public record Schema(String name, JsonNode json) {

    public static Schema load(String name) {
        try (InputStream in = Schema.class.getResourceAsStream("/schemas/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("no schema named " + name);
            return new Schema(name, Json.MAPPER.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
