package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.faultline.cli.Out;
import dev.faultline.cli.app.Deployment;
import dev.faultline.cli.scan.Risks.Risk;
import dev.faultline.cli.source.App;
import dev.faultline.cli.source.TestApps;
import dev.faultline.cli.understand.AppMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HintsTest {

    @Test
    void anAppWithoutAHintsFileHasNone(@TempDir Path dir) throws Exception {
        assertThat(Hints.read(dir)).isEqualTo(Hints.NONE);
    }

    @Test
    void readsAuthAndTheSeedFileNextToIt(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("faultline"));
        Files.writeString(dir.resolve("faultline/hints.yaml"), """
            auth:
              header: Authorization
              value: Bearer ${env:SHOP_TOKEN}
            seed: seed.sql
            """);
        Files.writeString(dir.resolve("faultline/seed.sql"), "insert into customers values ('c-1') on conflict do nothing;");

        var hints = Hints.read(dir);

        assertThat(hints.auth()).isEqualTo(new Plan.Auth("Authorization", "Bearer ${env:SHOP_TOKEN}"));
        assertThat(hints.seedFile(dir)).isEqualTo(Out.path(dir.resolve("faultline/seed.sql")));
    }

    @Test
    void aMissingSeedFileOrAnUnknownKeyIsAnError(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("faultline"));
        Files.writeString(dir.resolve("faultline/hints.yaml"), "seed: missing.sql\n");
        assertThatThrownBy(() -> Hints.read(dir)).hasMessageContaining("seed names missing.sql, but there is no such file next to it");

        Files.writeString(dir.resolve("faultline/hints.yaml"), "token: abc\n");
        assertThatThrownBy(() -> Hints.read(dir)).hasMessageContaining("cannot read");
    }

    @Test
    void thePlanCarriesTheHintsAndThePromptShowsTheTestData(@TempDir Path dir) throws Exception {
        var app = App.read(TestApps.payments(dir));
        Files.createDirectories(dir.resolve("faultline"));
        Files.writeString(dir.resolve("faultline/hints.yaml"), "auth: {header: X-Api-Key, value: \"${env:PAY_KEY}\"}\nseed: seed.sql\n");
        Files.writeString(dir.resolve("faultline/seed.sql"), "insert into payments values ('seeded-1') on conflict do nothing;\n");
        var jar = Deployment.jar("http://localhost:8081");
        var risk = new Risk("R1", "P1", "t", "PaymentListener#onMessage", "f", 1, "high", "w");
        var map = new AppMap("payments", "m", "Payments.", List.of(), List.of(), List.of(), List.of(),
            List.of(new AppMap.Journey("make-payment", "pay", List.of(new AppMap.Step("PaymentController#pay", "accept")))));
        var aim = PlanCommand.aim(app, map, risk, jar);
        var facts = PlanCommand.facts(app, jar, aim, Hints.read(dir));
        var draft = new PlanDraft("make-payment", new PlanDraft.Request("POST", "/api/payments", "{\"paymentId\": \"p-${seed}-${seq}\", \"amount\": 5}"),
            "paymentId", new PlanDraft.Effect("payments", "payment_id"), List.of("TRUNCATE payments"), List.of(), "w");

        Plan plan = PlanCommand.build(draft, app.name(), aim, facts, Map.of());

        assertThat(plan.target().auth()).isEqualTo(new Plan.Auth("X-Api-Key", "${env:PAY_KEY}"));
        assertThat(plan.database().seedFile()).isEqualTo(Out.path(dir.resolve("faultline/seed.sql")));
        assertThat(plan.problems()).isEmpty();
        assertThat(PlanCommand.prompt(app, map, aim, jar, facts)).contains("Test data Faultline loads after every reset").contains("seeded-1");
    }
}
