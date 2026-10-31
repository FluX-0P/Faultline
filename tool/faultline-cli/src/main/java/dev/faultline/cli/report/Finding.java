package dev.faultline.cli.report;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A risk the plan tested, and what the run made of it. {@code why} is the scan's reason for flagging it,
 * {@code reason} the run's reason for the label, and {@code evidence} the request IDs that broke a check.
 */
public record Finding(Label label, String reason, String riskId, String pattern, String title, String location,
                      String file, int line, String journey, String confidence, String why, List<String> evidence) {

    public enum Label {
        /** A check failed with the fault firing: the bug is real and reproducible. */
        @JsonProperty("confirmed") CONFIRMED,
        /** The scan flagged it, but the run could not break it. */
        @JsonProperty("suspected") SUSPECTED,
        /** The fault never fired (or there was none), so the run says nothing either way. */
        @JsonProperty("not tested") NOT_TESTED
    }
}
