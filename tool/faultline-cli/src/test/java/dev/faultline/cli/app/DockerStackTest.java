package dev.faultline.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import dev.faultline.cli.plan.TestPlans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class DockerStackTest {

    @Test
    void theOverrideGivesEveryServiceTheSettingsAndOnlyTheTargetTheAgent(@TempDir Path runDir) throws Exception {
        Path agentJar = runDir.resolve("faultline-agent.jar");
        try (var stack = new DockerStack(TestPlans.COMPOSE, runDir)) {
            stack.writeOverride(Map.of("payments.dedupe", "none"), Map.of("worker-1", Map.of("FAULTLINE_PLAN", "/faultline/run/agent-plan.properties")), agentJar);
        }

        JsonNode services = YAMLMapper.builder().build().readTree(Files.readString(runDir.resolve("compose.override.yml"))).path("services");
        assertThat(services.propertyNames()).containsExactly("api", "worker-1", "worker-2", "worker-3");
        assertThat(services.path("api").path("environment").path("SPRING_APPLICATION_JSON").asString()).isEqualTo("{\"payments.dedupe\":\"none\"}");
        assertThat(services.path("api").has("volumes")).isFalse();
        assertThat(services.path("worker-2").path("environment").has("JAVA_TOOL_OPTIONS")).isFalse();

        JsonNode worker1 = services.path("worker-1");
        assertThat(worker1.path("environment").path("JAVA_TOOL_OPTIONS").asString()).isEqualTo("-javaagent:/faultline/agent.jar");
        assertThat(worker1.path("environment").path("FAULTLINE_PLAN").asString()).isEqualTo("/faultline/run/agent-plan.properties");
        assertThat(worker1.path("volumes").path(0).path("source").asString()).isEqualTo(agentJar.toAbsolutePath().toString().replace('\\', '/'));
        assertThat(worker1.path("volumes").path(1).path("target").asString()).isEqualTo("/faultline/run");
    }
}
