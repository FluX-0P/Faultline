package dev.faultline.cli.workload;

/** One request the generator will send. {@code index} is its position in the run, from 0; {@code id} ties it to its effect. */
public record Request(int index, String id, String body) {}
