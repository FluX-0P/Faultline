package dev.faultline.cli;

import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;

/** The one JSON mapper the CLI uses. Jackson 3 writes java.time values as ISO-8601 strings by default. */
public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static void writePretty(Path file, Object value) {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }
}
