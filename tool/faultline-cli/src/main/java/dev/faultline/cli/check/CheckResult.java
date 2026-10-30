package dev.faultline.cli.check;

import java.util.List;

/** The verdict of one check. {@code offenders} lists every payment or account that broke it. */
public record CheckResult(String name, boolean passed, List<String> offenders) {

    static CheckResult of(String name, List<String> offenders) {
        return new CheckResult(name, offenders.isEmpty(), List.copyOf(offenders));
    }
}
