package dev.faultline.demo.payment;

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

    PaymentController(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @PostMapping("/payments")
    ResponseEntity<Void> pay(@RequestBody PaymentRequested req) throws Exception {
        kafka.send(TopicConfig.PAYMENTS_REQUESTED, req.accountId(), json.writeValueAsString(req)).get();
        return ResponseEntity.accepted().build();
    }
}
