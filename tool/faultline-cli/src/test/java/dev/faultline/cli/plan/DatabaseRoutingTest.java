package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.faultline.cli.app.Deployment;

/** Across services each table is in one database: Faultline runs every reset statement, rule and effect check there. */
class DatabaseRoutingTest {
    private static final PlanCommand.Facts FACTS = new PlanCommand.Facts(Deployment.jar("http://localhost:8080"), null, null, null, null,
        "localhost:9092", "order", "shipping", Map.of(), null, null,
        List.of(new PlanCommand.Db("dborder", "jdbc:postgresql://localhost:15432/dborder", "dbuser", "dbpass",
                Map.of("ordertable", Set.of("id", "shipping_street"), "order_line", Set.of("id"))),
            new PlanCommand.Db("dbshipping", "jdbc:postgresql://localhost:15432/dbshipping", "dbuser", "dbpass",
                Map.of("shipment", Set.of("id", "street")))),
        Map.of("POST /", "http://localhost:8081"));

    private static PlanDraft draft(String effect, List<String> reset, List<PlanDraft.Rule> rules) {
        return new PlanDraft("create-order", new PlanDraft.Request("POST", "/", "{}"), "shippingAddress.street",
            new PlanDraft.Effect(effect, "street"), reset, rules, "why");
    }

    @Test
    void eachTableHasItsDatabase() {
        assertThat(FACTS.databaseOf("SHIPMENT")).map(PlanCommand.Db::name).contains("dbshipping");
        assertThat(FACTS.databaseOf("invoice")).isEmpty();
        assertThat(PlanCommand.resetTable("TRUNCATE TABLE order_line CASCADE")).contains("order_line");
        assertThat(PlanCommand.resetTable("delete from shipment where true")).contains("shipment");
        assertThat(PlanCommand.databaseOfSql(FACTS, "select o.id from ordertable o join order_line l on l.id = o.id")).map(PlanCommand.Db::name)
            .contains("dborder");
        assertThat(PlanCommand.databaseOfSql(FACTS, "select s.id from shipment s join ordertable o on o.id = s.id")).isEmpty();
        assertThat(FACTS.url(new Plan.Request("POST", "/", "{}"))).isEqualTo("http://localhost:8081");
    }

    @Test
    void aDraftNamesTablesOfOneDatabaseEach() {
        var good = draft("shipment", List.of("TRUNCATE shipment", "TRUNCATE ordertable, order_line"),
            List.of(new PlanDraft.Rule("one-per-order", "why", "select id from ordertable group by id having count(*) > 1")));
        assertThat(PlanCommand.databaseProblems(good, FACTS)).isEmpty();

        var bad = draft("invoice", List.of("TRUNCATE shipment, ordertable", "TRUNCATE invoice"),
            List.of(new PlanDraft.Rule("across", "why", "select s.id from shipment s join ordertable o on o.id = s.id")));
        var problems = PlanCommand.databaseProblems(bad, FACTS);
        assertThat(problems).hasSize(4);
        assertThat(problems.get(0)).startsWith("effect.table 'invoice' must be a table of exactly one of the app's databases: dborder (");
        assertThat(problems.get(1)).isEqualTo("reset 'TRUNCATE shipment, ordertable' names tables of different databases: one statement per database");
        assertThat(problems.get(2)).startsWith("reset 'TRUNCATE invoice' must work on a table of exactly one of the app's databases");
        assertThat(problems.get(3)).startsWith("rule across must read the tables of one database");
    }
}
