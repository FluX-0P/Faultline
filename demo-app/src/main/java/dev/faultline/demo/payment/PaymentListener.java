package dev.faultline.demo.payment;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class PaymentListener {
    private final PaymentService service;
    private final JsonMapper json;

    PaymentListener(PaymentService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(topics = TopicConfig.PAYMENTS_REQUESTED, groupId = "payment-worker")
    void onMessage(String payload, Acknowledgment ack) {
        service.process(json.readValue(payload, PaymentRequested.class));
        ack.acknowledge();   // B1: a crash between the commit above and this line re-runs the payment
    }
}
