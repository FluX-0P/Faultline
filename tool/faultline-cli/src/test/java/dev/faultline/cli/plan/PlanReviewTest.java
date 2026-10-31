package dev.faultline.cli.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanReviewTest {

    private static Plan withRules() {
        var p = TestPlans.valid();
        return new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), p.kafka(), null, null, null, p.traffic(), p.correlateBy(),
            null, null, p.fault(), p.checks(),
            List.of(new Plan.Rule("balance-adds-up", "select 1", "the shop's balance is the sum of its payments", true), new Plan.Rule("mine", "select 2")),
            null, p.confirm());
    }

    private static Plan review(Plan plan, String answers, StringWriter printed) throws Exception {
        return PlanReview.review(plan, new BufferedReader(new StringReader(answers)), new PrintWriter(printed));
    }

    @Test
    void listsTheFaultEachCheckAndEachRuleWithWhatItMeans() {
        assertThat(PlanReview.items(withRules())).extracting(i -> i.kind() + " " + i.name() + ": " + i.what()).containsExactly(
            "fault code: crash before-ack, every 300th call, 3 times",
            "check no-lost: every request the app accepted was applied",
            "check no-duplicates: no request was applied twice",
            "check no-unexpected: nothing was applied that was never sent, or that failed",
            "check faults-fired: the fault fired every planned time; without it a pass may only mean it never happened",
            "rule balance-adds-up: drafted: the shop's balance is the sum of its payments",
            "rule mine: select 2");
    }

    @Test
    void enterKeepsAnItemAndNDropsIt() throws Exception {
        var printed = new StringWriter();
        // fault, no-lost, no-duplicates, no-unexpected, faults-fired, balance-adds-up, mine
        Plan reviewed = review(withRules(), "\ny\n\nno\n\nn\n\n", printed);

        assertThat(reviewed.fault()).isNotNull();
        assertThat(reviewed.checks()).containsExactly("no-lost", "no-duplicates", "faults-fired");
        assertThat(reviewed.rules()).extracting(Plan.Rule::id).containsExactly("mine");
        assertThat(reviewed.problems()).isEmpty();
        assertThat(printed.toString()).contains("dropped check no-unexpected, rule balance-adds-up");
    }

    @Test
    void droppingTheFaultTakesTheChecksThatNeedOneWithIt() throws Exception {
        var printed = new StringWriter();
        Plan reviewed = review(withRules(), "n\n", printed);   // the input ends: everything after the fault is kept

        assertThat(reviewed.fault()).isNull();
        assertThat(reviewed.checks()).containsExactly("no-lost", "no-duplicates", "no-unexpected");
        assertThat(reviewed.rules()).hasSize(2);
        assertThat(reviewed.problems()).isEmpty();
        assertThat(printed.toString()).containsPattern("faults-fired +goes with the fault").contains("can confirm nothing");
    }

    @Test
    void describesEveryKindOfFault() {
        assertThat(PlanReview.describe(new Plan.Fault(null, "MerchantBalance#read", "after", "barrier", 5L, 3, "3s", "worker-1", null,
            "MerchantBalance#write", List.of("worker-2", "worker-3"), null)))
            .isEqualTo("barrier after MerchantBalance#read on worker-1, every 5th call, 3 times, until MerchantBalance#write returns on worker-2 or worker-3, for up to 3s");
        assertThat(PlanReview.describe(new Plan.Fault("service", null, null, "outage", null, null, "6s", null, "5s")))
            .isEqualTo("the fake provider goes down 5s into the traffic, for 6s");
        assertThat(PlanReview.describe(new Plan.Fault("service", null, null, "late-reply", 100L, 5, "3s", null, null)))
            .isEqualTo("the fake provider: late-reply on every 100th charge, 5 times, 3s");
        assertThat(PlanReview.describe(new Plan.Fault("traffic", null, null, "duplicate", 50L, 20, null, null, null)))
            .isEqualTo("send every 50th request twice, 20 times");
        assertThat(PlanReview.describe(new Plan.Fault("messaging", null, null, "corrupt", 300L, 1, null, null, null)))
            .isEqualTo("the relay will corrupt every 300th message, once");
        assertThat(PlanReview.describe(new Plan.Fault("network", null, null, "latency", null, null, "15s", "worker-1-db", "5s", null, null, "30s")))
            .isEqualTo("latency worker-1-db 5s into the traffic, for 15s, latency 30s");
    }
}
