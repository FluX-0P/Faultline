package dev.faultline.demo.payment;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
class PaymentController {
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final String topic;

    PaymentController(KafkaTemplate<String, String> kafka, JsonMapper json, @Value("${payments.inbound-topic:payments.requested}") String topic) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
    }

    @PostMapping("/payments")
    ResponseEntity<Void> pay(@RequestBody PaymentRequested req) throws Exception {
        var event = new PaymentEvent(UUID.randomUUID().toString(), req.paymentId(), req.accountId(), req.amountMinor());
        kafka.send(topic, req.accountId(), json.writeValueAsString(event)).get();
        return ResponseEntity.accepted().build();
    }
}
