package dev.faultline.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RenameTest {
    @AfterEach
    void noRenames() {
        Rename.topics(Map.of());
    }

    @Test
    void aSendToARenamedTopicGoesToTheRelaysTopicWithEverythingElseKept() {
        Rename.topics(Map.of("orders", "orders.faultline"));
        var headers = List.of("trace");
        var sent = new ProducerRecord<>("orders", 2, 1_700L, "key", new byte[] {1, 2}, headers);

        var record = (ProducerRecord<?, ?>) Rename.of(sent);

        assertThat(record.topic()).isEqualTo("orders.faultline");
        assertThat(record.partition()).isEqualTo(2);
        assertThat(record.timestamp()).isEqualTo(1_700L);
        assertThat(record.key()).isEqualTo("key");
        assertThat(record.value()).isSameAs(sent.value());
        assertThat(record.headers()).isSameAs(headers);
    }

    @Test
    void anyOtherSendIsLeftAsItIs() {
        Rename.topics(Map.of("orders", "orders.faultline"));
        var sent = new ProducerRecord<>("payments", null, null, null, "v", List.of());

        assertThat(Rename.of(sent)).isSameAs(sent);
        assertThat(Rename.of(null)).isNull();
    }

    @Test
    void somethingThatIsNoProducerRecordIsLeftAsItIs() {
        Rename.topics(Map.of("orders", "orders.faultline"));

        assertThat(Rename.of("not a record")).isEqualTo("not a record");
    }
}
