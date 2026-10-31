package dev.faultline.cli;

import java.nio.file.Path;

/**
 * Faultline's own files about an app live outside the app, in the folder Faultline runs from:
 * .faultline/{app folder}/ holds app-map.json, risks.json and plan.yaml, and .faultline/cache/ the LLM answers.
 */
public final class Workspace {
    public static final Path ROOT = Path.of(".faultline");
    public static final String APP_MAP = "app-map.json";
    public static final String RISKS = "risks.json";
    public static final String PLAN = "plan.yaml";

    private Workspace() {}

    public static Path forApp(Path repo) {
        return ROOT.resolve(repo.toAbsolutePath().normalize().getFileName().toString());
    }

    public static Path cache() {
        return ROOT.resolve("cache");
    }
}
