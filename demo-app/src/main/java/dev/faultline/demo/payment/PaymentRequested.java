package dev.faultline.demo.payment;

public record PaymentRequested(String paymentId, String accountId, long amountMinor) {}
