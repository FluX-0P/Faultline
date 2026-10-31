package dev.faultline.cli.scan;

import java.util.List;

/**
 * risks.json: the candidates the LLM judged risky, most confident first, and the ones it dismissed, with its reasons.
 * {@code location} is Class#method.
 */
public record Risks(String app, String model, List<Risk> risks, List<Risk> dismissed) {

    public record Risk(String id, String pattern, String title, String location, String file, int line,
                       String confidence, String why) {}
}
