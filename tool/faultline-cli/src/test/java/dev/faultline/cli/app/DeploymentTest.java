package dev.faultline.cli.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DeploymentTest {

    @Test
    void findsTheAppsServicesItsConsumersItsApiAndFaultlinesHelpers() throws Exception {
        var d = TestDeployments.demo();

        // postgres and kafka stay up; the app's services and the helpers are recreated for each run
        assertThat(d.compose().services()).containsExactly("provider", "toxiproxy", "api", "worker-1", "worker-2", "worker-3");
        assertThat(d.compose().file()).isEqualTo("deploy/docker-compose.yml");
        assertThat(d.compose().profile()).isEqualTo("demo");
        assertThat(d.consumers()).containsExactly("worker-1", "worker-2", "worker-3");   // the api's listeners are off
        assertThat(d.compose().consumers()).isEqualTo(3);
        assertThat(d.url()).isEqualTo("http://localhost:8080");
        assertThat(d.stub()).isEqualTo("http://localhost:9090");
        assertThat(d.toxiproxy()).isEqualTo("http://localhost:8474");
        assertThat(d.faultTarget()).isEqualTo("worker-1");
    }

    @Test
    void theStubCountsOnlyWhenTheAppIsPointedAtIt() throws Exception {
        String config = TestDeployments.config().replace("http://provider:9090", "http://elsewhere:9090");

        assertThat(Deployment.parse(config, "deploy/docker-compose.yml", "demo", TestDeployments.DEMO_APP, 8080).stub()).isNull();
    }

    @Test
    void anotherAppsComposeFileIsNoDeploymentOfThisOne() throws Exception {
        assertThatThrownBy(() -> Deployment.parse(TestDeployments.config(), "deploy/docker-compose.yml", "demo", Path.of("../../tool"), 8080))
            .hasMessageContaining("has no service built from");
    }

    @Test
    void aJarIsOneProcessWithNoHelpers() {
        var d = Deployment.jar("http://localhost:8080");

        assertThat(d.compose()).isNull();
        assertThat(d.faultTarget()).isNull();
        assertThat(d.stub()).isNull();
    }
}
