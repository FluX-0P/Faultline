package dev.faultline.cli.doctor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.faultline.cli.app.ComposeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppStackTest {

    @Test
    void theAppIsTheServicesThatBuildASpringModuleOfTheRepo(@TempDir Path dir) throws Exception {
        var stack = read(TestStacks.shop(dir));

        // the Apache proxy and the Postgres image are built from the repo too, but are no Spring Boot module
        assertThat(stack.app).extracting(AppStack.AppService::name, AppStack.AppService::modulePath)
            .containsExactly(tuple("order", "shop/order"), tuple("shipping", "shop/shipping"));
        var byName = stack.byName();
        assertThat(byName.get("order").consumer()).isFalse();
        assertThat(byName.get("shipping").consumer()).isTrue();
        assertThat(byName.get("shipping").groups()).containsExactly("shipping");
        assertThat(byName.get("order").serverPort()).isEqualTo(8080);
    }

    @Test
    void theirConfigurationAsItRunsNamesTheDatabasesAndKafka(@TempDir Path dir) throws Exception {
        var stack = read(TestStacks.shop(dir));

        assertThat(stack.kafka.name()).isEqualTo("kafka");
        assertThat(stack.kafkaAddresses).containsExactly("kafka:9092");
        assertThat(stack.databases).extracting(d -> d.service().name(), AppStack.Database::address, AppStack.Database::name, AppStack.Database::usedBy)
            .containsExactly(
                tuple("postgres", "postgres:5432", "dborder", Set.of("order")),
                tuple("postgres", "postgres:5432", "dbshipping", Set.of("shipping")));
        assertThat(stack.databases).extracting(AppStack.Database::user, AppStack.Database::password)
            .containsExactly(tuple("dbuser", "dbpass"), tuple("dbuser", "from-the-shell"));
    }

    @Test
    void valuesComposeFillsInFromTheShellAreNotWrittenDown(@TempDir Path dir) throws Exception {
        var stack = read(TestStacks.shop(dir));

        var shipping = stack.byName().get("shipping");
        assertThat(shipping.unrecorded()).containsExactly("DB_PASSWORD");
        assertThat(shipping.recordedEnvironment()).containsOnlyKeys("SPRING_KAFKA_BOOTSTRAP_SERVERS");
        // its password came from the shell, so environment.json leaves it out; doctor still connects with it
        var dbshipping = stack.databases.stream().filter(d -> d.name().equals("dbshipping")).findFirst().orElseThrow();
        assertThat(dbshipping.recordedPassword()).isNull();
        // the order service's password is the module's own
        assertThat(stack.databases.getFirst().recordedPassword()).isEqualTo("dbpass");
    }

    @Test
    void aServiceThatReachesItsDatabaseThroughAProxyIsNoted(@TempDir Path dir) throws Exception {
        TestStacks.shop(dir);
        String config = TestStacks.config(dir, true).replace("\"SPRING_KAFKA_BOOTSTRAP_SERVERS\": \"kafka:9092\"}},",
            "\"SPRING_KAFKA_BOOTSTRAP_SERVERS\": \"kafka:9092\", \"SPRING_DATASOURCE_URL\": \"jdbc:postgresql://apache:5432/dborder\"}},");

        var stack = AppStack.read(dir.toAbsolutePath().normalize(), ComposeConfig.parse(config), ComposeConfig.parse(config));

        assertThat(stack.notes).anyMatch(n -> n.contains("order reaches its database through apache"));
        assertThat(stack.databases).extracting(AppStack.Database::name).containsExactly("dbshipping");
    }

    @Test
    void withDependenciesIsWhatComposeStartsForTheApp(@TempDir Path dir) throws Exception {
        var stack = read(TestStacks.shop(dir));

        assertThat(stack.config.withDependencies(List.of("order", "shipping"))).containsExactly("kafka", "order", "postgres", "shipping", "zookeeper");
        assertThat(stack.config.reachedAt("db")).map(ComposeConfig.Service::name).contains("postgres");
    }

    @Test
    void findsJarsTheDockerfilesCopyThatAreNotBuilt(@TempDir Path dir) throws Exception {
        var stack = read(TestStacks.shop(dir));

        assertThat(DoctorCommand.missingJars(stack.byName().get("order").service())).containsExactly("target/order-0.0.1-SNAPSHOT.jar");
        assertThat(DoctorCommand.missingJars(stack.byName().get("shipping").service())).isEmpty();
    }

    @Test
    void theHintsMayGiveAHelperAnotherImageButNeverTheApp(@TempDir Path dir) throws Exception {
        var stack = read(TestStacks.shop(dir));

        DoctorCommand.checkImages(Map.of("zookeeper", "zookeeper:3.4.14"), stack.config, stack);
        assertThat(stack.config.withImages(Map.of("zookeeper", "zookeeper:3.4.14")).services().get("zookeeper").image()).isEqualTo("zookeeper:3.4.14");
        assertThatThrownBy(() -> DoctorCommand.checkImages(Map.of("order", "order:1.0"), stack.config, stack))
            .hasMessageContaining("a service of the app keeps its own build");
        assertThatThrownBy(() -> DoctorCommand.checkImages(Map.of("zookeeper", "zookeeper:latest"), stack.config, stack))
            .hasMessageContaining("name an exact version");
        assertThatThrownBy(() -> DoctorCommand.checkImages(Map.of("zookeeper", "zookeeper"), stack.config, stack))
            .hasMessageContaining("name an exact version");
        assertThatThrownBy(() -> DoctorCommand.checkImages(Map.of("zk", "zookeeper:3.4.14"), stack.config, stack))
            .hasMessageContaining("no service of that name");
    }

    @Test
    void aSkippedServiceIsLeftOutWithTheDatabasesOnlyItUses(@TempDir Path dir) throws Exception {
        TestStacks.shop(dir);
        Path repo = dir.toAbsolutePath().normalize();
        var stack = AppStack.read(repo, ComposeConfig.parse(TestStacks.config(dir, true)), ComposeConfig.parse(TestStacks.config(dir, false)), List.of("order"));

        assertThat(stack.app).extracting(AppStack.AppService::name).containsExactly("shipping");
        assertThat(stack.skipped).containsExactly("order");
        assertThat(stack.databases).extracting(AppStack.Database::name).containsExactly("dbshipping");
        // the proxy depends on order: booting it would start order anyway
        assertThatThrownBy(() -> DoctorCommand.checkSkipped(List.of("order"), Set.of("apache", "shipping"), stack.config))
            .hasMessageContaining("cannot skip order: apache depends on it");
        DoctorCommand.checkSkipped(List.of("order"), Set.of("kafka", "postgres", "shipping", "zookeeper"), stack.config);
        assertThatThrownBy(() -> AppStack.read(repo, stack.config, stack.config, List.of("kafka"))).hasMessageContaining("skip names kafka, which is no service of the app");
    }

    @Test
    void anImageWithAnOldV1ManifestIsSaidSoWithTheHintThatHelps(@TempDir Path dir) throws Exception {
        var stack = read(TestStacks.shop(dir));
        var failed = new IllegalStateException("docker compose up ... failed:\n   Image wurstmeister/zookeeper:3.4.6 Error not implemented: media type "
            + "\"application/vnd.docker.distribution.manifest.v1+prettyjws\" is no longer supported since containerd v2.1");

        assertThat(DoctorCommand.unpullable(failed, stack.config)).get().extracting(Throwable::getMessage).asString()
            .startsWith("wurstmeister/zookeeper:3.4.6 (service zookeeper) cannot be pulled")
            .endsWith("such as images: {zookeeper: IMAGE:VERSION}");
        assertThat(DoctorCommand.unpullable(new IllegalStateException("build failed"), stack.config)).isEmpty();
    }

    @Test
    void readsComposePsInEitherFormatAndSkipsWarnings() {
        String lines = "time=\"x\" level=warning msg=\"the attribute `version` is obsolete\"\n"
            + "{\"Service\":\"kafka\",\"State\":\"running\",\"Health\":\"\",\"ExitCode\":0}\n{\"Service\":\"init\",\"State\":\"exited\",\"ExitCode\":0}\n";
        assertThat(DoctorCommand.jsonLines(lines)).extracting(n -> n.path("Service").asString()).containsExactly("kafka", "init");
        assertThat(DoctorCommand.jsonLines("[{\"Service\":\"a\"},{\"Service\":\"b\"}]")).hasSize(2);
        assertThat(ComposeConfig.parse("time=\"x\" level=warning msg=\"obsolete\"\n{\"name\": \"p\", \"services\": {}}").project()).isEqualTo("p");
    }

    private static AppStack read(Path dir) throws Exception {
        Path repo = dir.toAbsolutePath().normalize();
        return AppStack.read(repo, ComposeConfig.parse(TestStacks.config(dir, true)), ComposeConfig.parse(TestStacks.config(dir, false)));
    }
}
