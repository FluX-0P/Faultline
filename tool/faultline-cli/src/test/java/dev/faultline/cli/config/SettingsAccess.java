package dev.faultline.cli.config;

import java.util.Map;

/** Settings for a test from these environment variables alone, with no .env read. */
public final class SettingsAccess {
    private SettingsAccess() {}

    public static Settings of(Map<String, String> env) {
        return new Settings(env, Map.of());
    }
}
