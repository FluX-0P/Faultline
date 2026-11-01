package dev.faultline.cli.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;

import org.junit.jupiter.api.Test;

class RequestTemplateTest {
    static final String PAYMENT = "{\"paymentId\":\"pay-${seed}-${seq}\",\"accountId\":\"acct-${int:1:100}\",\"amountMinor\":${int:100:5000}}";

    @Test
    void seed42StaysTheSameForever() {
        // pinned values, the same the Phase 2 generator gave: if these change, old reproduce commands no longer reproduce
        var requests = Workload.generate(RequestTemplate.parse(PAYMENT), "paymentId", 42, 3);
        assertThat(requests).containsExactly(
            new Request(0, "pay-42-0", "{\"paymentId\":\"pay-42-0\",\"accountId\":\"acct-31\",\"amountMinor\":4111}"),
            new Request(1, "pay-42-1", "{\"paymentId\":\"pay-42-1\",\"accountId\":\"acct-49\",\"amountMinor\":3479}"),
            new Request(2, "pay-42-2", "{\"paymentId\":\"pay-42-2\",\"accountId\":\"acct-71\",\"amountMinor\":3835}"));
    }

    @Test
    void sameSeedGivesSameRequestsAndOtherSeedsDoNot() {
        var template = RequestTemplate.parse(PAYMENT);
        assertThat(Workload.generate(template, "paymentId", 7, 500)).isEqualTo(Workload.generate(template, "paymentId", 7, 500));
        assertThat(Workload.generate(template, "paymentId", 7, 500)).isNotEqualTo(Workload.generate(template, "paymentId", 8, 500));
    }

    @Test
    void randomNumbersStayInTheirRange() {
        var template = RequestTemplate.parse("${int:-2:2}");
        var random = new Random(1);
        for (int i = 0; i < 1_000; i++) {
            assertThat(Integer.parseInt(template.render(1, i, random))).isBetween(-2, 2);
        }
    }

    @Test
    void rejectsUnknownPlaceholdersAndBadRanges() {
        assertThatThrownBy(() -> RequestTemplate.parse("${uuid}")).hasMessageContaining("unknown placeholder ${uuid}");
        assertThatThrownBy(() -> RequestTemplate.parse("${int:5:1}")).hasMessageContaining("MIN is larger than MAX");
        assertThatThrownBy(() -> RequestTemplate.parse(" ")).hasMessageContaining("empty");
    }

    @Test
    void seqAndSeedCanBePaddedWithZerosToAFixedLength() {
        var uuid = RequestTemplate.parse("00000000-0000-0000-${seq:4}-${seed:12}");

        assertThat(uuid.render(319008, 17, new Random(1))).isEqualTo("00000000-0000-0000-0017-000000319008");
        // more digits than the width: as they are
        assertThat(RequestTemplate.parse("${seq:2}").render(42, 1234, new Random(1))).isEqualTo("1234");
        assertThatThrownBy(() -> RequestTemplate.parse("${seq:0}")).hasMessageContaining("at least 1");
    }

    @Test
    void theIdMustBeInTheBody() {
        assertThatThrownBy(() -> Workload.generate(RequestTemplate.parse("{\"a\":1}"), "paymentId", 1, 1))
            .hasMessageContaining("no field 'paymentId'");
        assertThatThrownBy(() -> Workload.generate(RequestTemplate.parse("{not json"), "paymentId", 1, 1))
            .hasMessageContaining("not valid JSON");
    }
}
