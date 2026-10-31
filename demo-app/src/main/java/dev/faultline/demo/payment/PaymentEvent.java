package dev.faultline.demo.payment;

/** What goes through Kafka: the payment as requested, plus an ID for this one submission of it. */
public record PaymentEvent(String eventId, String paymentId, String accountId, long amountMinor) {}
