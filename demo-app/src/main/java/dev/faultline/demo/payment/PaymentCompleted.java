package dev.faultline.demo.payment;

/** What the rest of the system hears once a payment has gone through, on payments.completed. */
public record PaymentCompleted(String paymentId, String accountId, long amountMinor) {}
