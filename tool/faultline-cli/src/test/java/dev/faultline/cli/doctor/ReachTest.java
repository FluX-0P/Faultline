package dev.faultline.cli.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.faultline.cli.app.ComposeConfig;
import org.junit.jupiter.api.Test;

class ReachTest {

    @Test
    void aListenerAdvertisedAsLocalhostAndPublishedOnThatPortIsUsedAsItIs() {
        // BankForge: PLAINTEXT_HOST binds 29092, advertises localhost:29092, and 29092 is published
        var kafka = kafka("apache/kafka:3.9.2", Map.of(
            "KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,PLAINTEXT_HOST://0.0.0.0:29092",
            "KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092"), new ComposeConfig.Port(29092, 29092));
        var reach = new Reach(port -> true);

        assertThat(reach.kafka(kafka)).map(Reach.Address::host).contains("localhost:29092");
        assertThat(reach.override()).isEmpty();
    }

    @Test
    void aPublishedPortThatDiffersFromTheAdvertisedOneIsNotEnough() {
        // KHolodilin: binds 9092, advertises localhost:19092, publishes 19092:9092, which works; 9092:9092 would not
        var works = kafka("apache/kafka:3.9.1", Map.of(
            "KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:29092,PLAINTEXT_HOST://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093",
            "KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:19092",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT"), new ComposeConfig.Port(9092, 19092));
        assertThat(new Reach(port -> true).kafka(works)).map(Reach.Address::host).contains("localhost:19092");

        var wrong = kafka("apache/kafka:3.9.1", works.environment(), new ComposeConfig.Port(9092, 9092));
        var reach = new Reach(port -> true);
        assertThat(reach.kafka(wrong)).map(Reach.Address::host).contains("localhost:39092");
        assertThat(environment(reach, "kafka")).containsEntry("KAFKA_ADVERTISED_LISTENERS",
            "PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:19092,FAULTLINE://localhost:39092");
    }

    @Test
    void wurstmeistersOlderSettingsBecomeListenersWithOneForThisMachine() {
        // ewolff: only KAFKA_ADVERTISED_HOST_NAME and _PORT, no published port
        var kafka = kafka("wurstmeister/kafka:2.13-2.8.1", Map.of("KAFKA_ADVERTISED_HOST_NAME", "kafka", "KAFKA_ADVERTISED_PORT", "9092"));
        var reach = new Reach(port -> port != 39092);   // something else has 39092

        var address = reach.kafka(kafka);

        assertThat(address).map(Reach.Address::host).contains("localhost:39093");
        assertThat(environment(reach, "kafka")).containsExactlyInAnyOrderEntriesOf(Map.of(
            "KAFKA_LISTENERS", "PLAINTEXT://:9092,FAULTLINE://:39092",
            "KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:9092,FAULTLINE://localhost:39093",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,FAULTLINE:PLAINTEXT",
            "KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT"));
        assertThat(ports(reach, "kafka")).containsExactly("127.0.0.1:39093:39092");
        assertThat(reach.publishedPorts()).containsExactly(39093);
    }

    @Test
    void bitnamiSettingsHaveTheirOwnPrefixAndAMissingMapIsMadeFromProtocolNames() {
        var kafka = kafka("bitnami/kafka:3.7", Map.of(
            "KAFKA_CFG_LISTENERS", "PLAINTEXT://:9092", "KAFKA_CFG_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:9092"));
        var reach = new Reach(port -> true);

        assertThat(reach.kafka(kafka)).isPresent();
        assertThat(environment(reach, "kafka")).containsEntry("KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,FAULTLINE:PLAINTEXT")
            .containsEntry("KAFKA_CFG_LISTENERS", "PLAINTEXT://:9092,FAULTLINE://:39092");
    }

    @Test
    void anUnknownImageOrSetupIsLeftToTheHints() {
        assertThat(new Reach(port -> true).kafka(kafka("redpandadata/redpanda:v24.1.1", Map.of()))).isEmpty();
        // named listeners without a protocol map: Faultline cannot tell their protocols
        assertThat(new Reach(port -> true).kafka(kafka("apache/kafka:3.9.1", Map.of(
            "KAFKA_LISTENERS", "INTERNAL://:9092", "KAFKA_ADVERTISED_LISTENERS", "INTERNAL://kafka:9092")))).isEmpty();
    }

    @Test
    void aDatabasesPublishedPortIsUsedElseTheOverridePublishesOneOnLocalhost() {
        var published = service("account-db", "postgres:17-alpine", Map.of(), new ComposeConfig.Port(5432, 5433));
        var hidden = service("postgres", "", Map.of());
        var reach = new Reach(port -> port != 15432);

        assertThat(reach.database(published, 5432).host()).isEqualTo("localhost:5433");
        assertThat(reach.database(hidden, 5432).host()).isEqualTo("localhost:15433");
        // the same server again, such as a second database on it: the same port
        assertThat(reach.database(hidden, 5432).host()).isEqualTo("localhost:15433");
        assertThat(ports(reach, "postgres")).containsExactly("127.0.0.1:15433:5432");
        assertThat(reach.override()).containsOnlyKeys("services");
    }

    private static ComposeConfig.Service kafka(String image, Map<String, String> env, ComposeConfig.Port... ports) {
        return service("kafka", image, env, ports);
    }

    private static ComposeConfig.Service service(String name, String image, Map<String, String> env, ComposeConfig.Port... ports) {
        return new ComposeConfig.Service(name, image, null, null, List.of(ports), env, Set.of(name), Set.of(), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environment(Reach reach, String service) {
        var services = (Map<String, Map<String, Object>>) reach.override().get("services");
        return (Map<String, String>) services.get(service).get("environment");
    }

    @SuppressWarnings("unchecked")
    @Test
    void portsTheHintsMoveReplaceTheServicesOwnInTheOverride() {
        var reach = new Reach(port -> true);
        reach.movePorts("cart-db", List.of("15432:5432"));

        String yaml = Reach.yaml("services:\n  cart-db:\n    " + Reach.MOVED_PORTS + ":\n    - \"15432:5432\"\n");

        assertThat(yaml).isEqualTo("services:\n  cart-db:\n    ports: !override\n    - \"15432:5432\"\n");
        var config = new ComposeConfig("shop", Map.of("cart-db", new ComposeConfig.Service("cart-db", "postgres:15", null, null,
            List.of(new ComposeConfig.Port(5432, 52432)), Map.of(), Set.of("cart-db"), Set.of(), null)));
        assertThat(config.withPorts(Map.of("cart-db", List.of("127.0.0.1:15432:5432"))).services().get("cart-db").published(5432)).hasValue(15432);
    }

    @Test
    void aPortWindowsKeepsForItselfIsInOneOfItsExcludedRanges() {
        String netsh = """

            Protocol tcp Port Exclusion Ranges

            Start Port    End Port
            ----------    --------
                 50000       50059     *
                 52432       52531

            * - Administered port exclusions.
            """;

        assertThat(Ports.reservedIn(netsh, 52432)).hasValue("52432-52531");
        assertThat(Ports.reservedIn(netsh, 50059)).hasValue("50000-50059");
        assertThat(Ports.reservedIn(netsh, 53432)).isEmpty();
    }

    private static List<String> ports(Reach reach, String service) {
        var services = (Map<String, Map<String, Object>>) reach.override().get("services");
        return (List<String>) services.get(service).get("ports");
    }
}
