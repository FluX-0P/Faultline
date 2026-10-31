package dev.faultline.cli.plan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.faultline.cli.Out;

/**
 * faultline plan's review: the plan's fault, checks and rules one at a time, each kept or dropped. Enter (or y) keeps an
 * item, n drops it. Without its fault a plan can confirm nothing, so the checks that need one (faults-fired and
 * recovery-within) go with it.
 */
final class PlanReview {
    /** One thing the plan does: a fault, a check or a rule, with what it means. */
    record Item(String kind, String name, String what) {}

    static final Map<String, String> CHECKS = Map.of(
        "no-lost", "every request the app accepted was applied",
        "no-duplicates", "no request was applied twice",
        "no-unexpected", "nothing was applied that was never sent, or that failed",
        Plan.PER_KEY_ORDER, "requests with the same key were applied in the order they happened",
        Plan.NO_LOST_DOWNSTREAM, "every applied request also produced its downstream event",
        Plan.RECOVERY_WITHIN, "once the fault ended, the app caught up in time",
        Plan.FAULTS_FIRED, "the fault fired every planned time; without it a pass may only mean it never happened");
    private static final Set<String> NEED_A_FAULT = Set.of(Plan.FAULTS_FIRED, Plan.RECOVERY_WITHIN);

    private PlanReview() {}

    static List<Item> items(Plan plan) {
        var items = new ArrayList<Item>();
        if (plan.fault() != null) items.add(new Item("fault", plan.fault().layer() == null ? "code" : plan.fault().layer(), describe(plan.fault())));
        for (String check : plan.checks() == null ? List.<String>of() : plan.checks()) items.add(new Item("check", check, CHECKS.getOrDefault(check, "")));
        for (var rule : plan.rules() == null ? List.<Plan.Rule>of() : plan.rules()) {
            items.add(new Item("rule", rule.id(), (rule.byLlm() ? "drafted: " : "") + (rule.why() != null ? rule.why() : rule.sql().strip())));
        }
        return items;
    }

    /** Asks about each item and returns the plan without the ones dropped. The end of the input keeps the rest. */
    static Plan review(Plan plan, BufferedReader in, PrintWriter out) throws IOException {
        Out.line(out, "review    Enter or y keeps an item, n drops it");
        var dropped = new LinkedHashSet<Item>();
        boolean faultDropped = false;
        boolean ended = false;
        for (Item item : items(plan)) {
            if (faultDropped && item.kind().equals("check") && NEED_A_FAULT.contains(item.name())) {
                Out.line(out, "  %-6s %-26s goes with the fault", item.kind(), item.name());
                continue;
            }
            out.print("  %-6s %-26s %s%n         keep? [Y/n] ".formatted(item.kind(), item.name(), item.what()));
            out.flush();
            String reply = ended ? null : in.readLine();
            if (reply == null) {
                if (!ended) Out.line(out, "");
                ended = true;
            } else if (reply.strip().toLowerCase(Locale.ROOT).startsWith("n")) {
                dropped.add(item);
                if (item.kind().equals("fault")) faultDropped = true;
            }
        }
        if (dropped.isEmpty()) {
            Out.line(out, "review    kept everything");
        } else {
            Out.line(out, "review    dropped %s", String.join(", ", dropped.stream().map(i -> i.kind() + " " + i.name()).toList()));
            if (faultDropped) Out.line(out, "          without a fault the runs can confirm nothing: every finding will be not tested");
        }
        return without(plan, dropped);
    }

    static Plan without(Plan p, Set<Item> dropped) {
        boolean noFault = dropped.stream().anyMatch(i -> i.kind().equals("fault"));
        var checks = p.checks() == null ? null : p.checks().stream()
            .filter(c -> dropped.stream().noneMatch(i -> i.kind().equals("check") && i.name().equals(c)))
            .filter(c -> !noFault || !NEED_A_FAULT.contains(c)).toList();
        var rules = p.rules() == null ? null : p.rules().stream()
            .filter(r -> dropped.stream().noneMatch(i -> i.kind().equals("rule") && i.name().equals(r.id()))).toList();
        return new Plan(p.app(), p.journey(), p.risk(), p.target(), p.database(), p.kafka(), p.relay(), p.toxiproxy(), p.stub(), p.traffic(),
            p.correlateBy(), p.downstream(), p.order(), noFault ? null : p.fault(), checks, rules == null || rules.isEmpty() ? null : rules,
            p.recoveryWithin(), p.confirm());
    }

    /** A fault in one line, such as "crash before-ack on worker-1, every 150th call, 2 times". */
    static String describe(Plan.Fault f) {
        String layer = f.layer() == null ? "code" : f.layer();
        String times = f.count() == 1 ? "once" : f.count() + " times";
        return switch (layer) {
            case "code" -> {
                String where = f.point().equals("before-ack") ? "before-ack" : (f.when() == null ? "before" : f.when()) + " " + f.point();
                String extra = "barrier".equals(f.action()) ? ", until %s returns on %s, for up to %s".formatted(f.until(), String.join(" or ", f.partnerServices()), f.duration())
                    : "delay".equals(f.action()) ? ", for " + f.duration() : "";
                yield "%s %s%s, every %dth call, %s%s".formatted(f.action(), where, f.target() == null ? "" : " on " + f.target(), f.nthHit(), times, extra);
            }
            case "process", "network" -> "%s %s %s into the traffic, for %s%s".formatted(f.action(), f.target(), f.at(), f.duration(),
                f.latency() == null ? "" : ", latency " + f.latency());
            case "service" -> "outage".equals(f.action())
                ? "the fake provider goes down %s into the traffic, for %s".formatted(f.at(), f.duration())
                : "the fake provider: %s on every %dth charge, %s%s".formatted(f.action(), f.nthHit(), times, f.duration() == null ? "" : ", " + f.duration());
            case "traffic" -> "send every %dth request twice, %s".formatted(f.nthHit(), times);
            case "messaging" -> "the relay will %s every %dth message, %s".formatted(f.action(), f.nthHit(), times);
            default -> layer + " " + f.action();
        };
    }
}
