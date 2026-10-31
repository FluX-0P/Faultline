package dev.faultline.cli.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The demo app's deployment, from compose-config.json: what {@code docker compose --profile demo config --format json}
 * printed for deploy/docker-compose.yml, with the build folders put back where this checkout has them.
 */
public final class TestDeployments {
    public static final Path DEMO_APP = Path.of("../../demo-app");

    private TestDeployments() {}

    public static Deployment demo() throws IOException {
        return Deployment.parse(config(), "deploy/docker-compose.yml", "demo", DEMO_APP, 8080);
    }

    static String config() throws IOException {
        try (InputStream in = TestDeployments.class.getResourceAsStream("/compose-config.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return json.replace("${DEMO_APP}", jsonPath(DEMO_APP)).replace("${CLI}", jsonPath(Path.of(".")));
        }
    }

    private static String jsonPath(Path p) {
        return p.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }
}
