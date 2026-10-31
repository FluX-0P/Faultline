package dev.faultline.cli.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import dev.faultline.cli.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultsTest {

    @Test
    void writesThePlanTheAgentReads(@TempDir Path runDir) throws Exception {
        var fault = new Plan.Fault(null, "PaymentService#apply", "after", "delay", 200L, 1, "15s", "worker-1", null);

        var env = Faults.writeAgentPlan(fault, runDir, "/faultline/run", false);

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
    void aBarrierListensForItsPartnersWhoSignalTheTargetByName(@TempDir Path runDir) throws Exception {
        var fault = new Plan.Fault(null, "MerchantBalance#read", "after", "barrier", 5L, 3, "3s", "worker-1", null,
            "MerchantBalance#write", List.of("worker-2", "worker-3"), null);

        Faults.writeAgentPlan(fault, runDir, "/faultline/run", false);
        var partnerEnv = Faults.writePartnerPlan(fault, runDir, "/faultline/run");

        assertThat(read(runDir.resolve("agent-plan.properties"))).containsEntry("action", "barrier")
            .containsEntry("until", "MerchantBalance#write").containsEntry("listen", "7071").containsEntry("delay_ms", "3000");
        assertThat(partnerEnv).containsEntry("FAULTLINE_PLAN", "/faultline/run/agent-plan-partner.properties");
        assertThat(read(runDir.resolve("agent-plan-partner.properties"))).containsEntry("point", "MerchantBalance#write")
            .containsEntry("when", "after").containsEntry("action", "signal").containsEntry("signal_to", "worker-1:7071");
    }

    private static Properties read(Path file) throws Exception {
        var plan = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            plan.load(in);
        }
        return plan;
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
