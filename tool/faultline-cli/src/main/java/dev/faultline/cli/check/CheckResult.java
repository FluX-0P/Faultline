package dev.faultline.cli.check;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The verdict of one check. {@code offenders} lists every payment or account that broke it; {@code note} (may be null)
 * says what was measured, such as how long the app took to catch up.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckResult(String name, boolean passed, List<String> offenders, String note) {

    public CheckResult(String name, boolean passed, List<String> offenders) {
        this(name, passed, offenders, null);
    }

    static CheckResult of(String name, List<String> offenders) {
        return of(name, offenders, null);
    }

    static CheckResult of(String name, List<String> offenders, String note) {
        return new CheckResult(name, offenders.isEmpty(), List.copyOf(offenders), note);
    }
}
