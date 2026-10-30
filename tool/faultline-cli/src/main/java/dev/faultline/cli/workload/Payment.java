package dev.faultline.cli.workload;

/** One payment the generator will send. {@code index} is its position in the run, starting at 0. */
public record Payment(int index, String paymentId, String accountId, long amountMinor) {}
