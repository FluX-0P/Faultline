package dev.faultline.cli.scan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The LLM's verdict on every candidate of one pattern: is the risk real, how sure is it, and why. */
public record PatternReview(List<Verdict> verdicts) {
    static final Set<String> CONFIDENCE = Set.of("low", "medium", "high");

    public record Verdict(String candidate, Boolean risky, String confidence, String why) {}

    /** Every candidate answered exactly once, with a confidence and a reason. */
    List<String> problems(List<String> candidateIds) {
        var problems = new ArrayList<String>();
        if (verdicts == null) return List.of("verdicts is missing");
        var seen = new HashSet<String>();
        for (var v : verdicts) {
            if (!candidateIds.contains(v.candidate())) problems.add("there is no candidate " + v.candidate() + "; use " + candidateIds);
            else if (!seen.add(v.candidate())) problems.add("candidate " + v.candidate() + " is answered twice");
            if (v.risky() == null) problems.add(v.candidate() + ": risky must be true or false");
            if (!CONFIDENCE.contains(v.confidence())) problems.add(v.candidate() + ": confidence must be low, medium or high");
            if (v.why() == null || v.why().isBlank()) problems.add(v.candidate() + ": why is empty");
        }
        for (var id : candidateIds) {
            if (!seen.contains(id)) problems.add("candidate " + id + " has no verdict");
        }
        return problems;
    }
}
