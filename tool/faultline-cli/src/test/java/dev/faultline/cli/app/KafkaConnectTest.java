package dev.faultline.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.faultline.cli.Json;

class KafkaConnectTest {
    private static int running(String json) {
        return KafkaConnect.running(Json.MAPPER.readTree(json));
    }

    @Test
    void readyOnceEveryConnectorAndEveryTaskRuns() {
        assertThat(running("""
            {"account-outbox": {"status": {"connector": {"state": "RUNNING"}, "tasks": [{"id": 0, "state": "RUNNING"}]}},
             "ledger-outbox": {"status": {"connector": {"state": "RUNNING"}, "tasks": [{"id": 0, "state": "RUNNING"}]}}}""")).isEqualTo(2);
    }

    @Test
    void notReadyWhileOneIsNot() {
        assertThat(running("{}")).isZero();
        assertThat(running("""
            {"account-outbox": {"status": {"connector": {"state": "RUNNING"}, "tasks": [{"id": 0, "state": "RUNNING"}]}},
             "ledger-outbox": {"status": {"connector": {"state": "RUNNING"}, "tasks": []}}}""")).isZero();
        assertThat(running("""
            {"account-outbox": {"status": {"connector": {"state": "FAILED"}, "tasks": [{"id": 0, "state": "FAILED"}]}}}""")).isZero();
    }
}
