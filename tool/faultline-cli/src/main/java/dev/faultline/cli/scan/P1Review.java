package dev.faultline.cli.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The LLM's verdict on one P1 candidate: is there a duplicate check in the same transaction as the work? */
public record P1Review(String duplicateCheck, String confidence, String why) {
    static final Set<String> CONFIDENCE = Set.of("low", "medium", "high");

    public boolean risky() {
        return "no".equals(duplicateCheck);
    }

    List<String> problems() {
        var problems = new ArrayList<String>();
        if (!"yes".equals(duplicateCheck) && !"no".equals(duplicateCheck)) problems.add("duplicateCheck must be yes or no");
        if (!CONFIDENCE.contains(confidence)) problems.add("confidence must be low, medium or high");
        if (why == null || why.isBlank()) problems.add("why is empty");
        return problems;
    }
}
