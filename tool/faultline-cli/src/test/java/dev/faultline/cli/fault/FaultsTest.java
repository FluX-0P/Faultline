package dev.faultline.cli.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import dev.faultline.cli.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultsTest {

    @Test
    void writesThePlanTheAgentReads(@TempDir Path runDir) throws Exception {
        var fault = new Plan.Fault(null, "PaymentService#apply", "after", "delay", 200L, 1, "15s", "worker-1", null);

        var env = Faults.writeAgentPlan(fault, runDir, "/faultline/run");

        var plan = new Properties();
        try (Reader in = Files.newBufferedReader(runDir.resolve("agent-plan.properties"))) {
            plan.load(in);
        }
        assertThat(env).containsEntry("FAULTLINE_PLAN", "/faultline/run/agent-plan.properties");
        assertThat(plan).containsEntry("point", "PaymentService#apply").containsEntry("when", "after")
            .containsEntry("action", "delay").containsEntry("delay_ms", "15000")
            .containsEntry("nth_hit", "200").containsEntry("times", "1")
            .containsEntry("log", "/faultline/run/faults.jsonl");
    }

    @Test
    void countsFaultsFromTheAgentAndTheCli(@TempDir Path runDir) throws Exception {
        assertThat(Faults.fired(runDir)).isZero();

        Files.writeString(runDir.resolve("faults.jsonl"), "{\"point\":\"before-ack\"}\n");
        Faults.logFired(runDir, "process", "pause", "worker-1");

        assertThat(Faults.fired(runDir)).isEqualTo(2);
        assertThat(Files.readAllLines(runDir.resolve("faults.jsonl")).get(1))
            .startsWith("{\"point\":\"process\",\"action\":\"pause\",\"target\":\"worker-1\",\"at\":");
    }
}
