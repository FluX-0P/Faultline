package dev.faultline.cli.scan;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * risks.json: the candidates the LLM judged risky, worst outcome and most confident first, and the ones it dismissed,
 * with its reasons. {@code location} is Class#method; {@code saw} is what the static check found there.
 * {@code settings} (may be null) are the settings the app was judged with on top of its own configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Risks(String app, String model, Map<String, String> settings, List<Risk> risks, List<Risk> dismissed) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Risk(String id, String pattern, String title, String location, String file, int line,
                       String confidence, String why, String outcome, String saw) {
        public Risk(String id, String pattern, String title, String location, String file, int line, String confidence, String why) {
            this(id, pattern, title, location, file, line, confidence, why, null, null);
        }
    }
}
