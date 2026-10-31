package dev.faultline.cli.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import dev.faultline.cli.Json;
import dev.faultline.cli.check.CheckResult;
import dev.faultline.cli.plan.Plan;
import dev.faultline.cli.report.Finding.Label;
import dev.faultline.cli.run.RunInfo;
import dev.faultline.cli.run.RunResult;

/**
 * report.html: one self-contained page per run, from the run folder's run.json, result.json and plan.yaml.
 * Confirmed bugs lead, each with its location, journey, evidence and reproduce command; the plan that ran is at the end.
 */
public final class ReportWriter {
    public static final String FILE = "report.html";
    static final int SHOW_IDS = 20;

    private ReportWriter() {}

    public static Path write(Path runDir) throws IOException {
        var info = Json.MAPPER.readValue(runDir.resolve("run.json").toFile(), RunInfo.class);
        var result = Json.MAPPER.readValue(runDir.resolve("result.json").toFile(), RunResult.class);
        Path planFile = runDir.resolve("plan.yaml");
        Plan plan = Plan.read(planFile);
        String html = render(info, result, plan, Files.readString(planFile, StandardCharsets.UTF_8), runDir.getFileName().toString());
        Path file = runDir.resolve(FILE);
        Files.writeString(file, html, StandardCharsets.UTF_8);
        return file;
    }

    static String render(RunInfo info, RunResult result, Plan plan, String planText, String runName) {
        long confirmed = result.findings().stream().filter(f -> f.label() == Label.CONFIRMED).count();
        boolean pass = result.verdict().equals("PASS");
        String headline = confirmed > 0 ? confirmed + (confirmed == 1 ? " confirmed bug" : " confirmed bugs")
            : pass ? "No confirmed bugs" : "Checks failed, but no bug is confirmed";
        String sub = confirmed > 0 ? "Faultline broke the app on purpose and the data came out wrong. Each bug below reproduces with its command."
            : pass ? "Every check passed." : "See the checks below: a failure without the planned fault firing proves nothing yet.";

        var html = new StringBuilder();
        html.append("""
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Faultline report: %s</title>
            <style>%s</style>
            </head>
            <body>
            <main>
            <header>
              <div class="eyebrow">Faultline report</div>
              <h1>%s</h1>
              <div class="meta">Run %s &middot; seed %d &middot; %,d requests at %d/s &middot; plan %s</div>
            </header>
            <section class="verdict %s">
              <span class="chip %s">%s</span>
              <div><div class="big">%s</div><div>%s</div></div>
            </section>
            """.formatted(esc(plan.app()), CSS, esc(plan.app()), esc(runName), info.seed(), info.count(), info.rate(), esc(info.plan()),
            pass ? "pass" : "fail", pass ? "pass" : "fail", pass ? "PASS" : "FAIL", esc(headline), esc(sub)));

        html.append("<h2>Findings</h2>\n");
        if (result.findings().isEmpty()) {
            html.append("<p class=\"muted\">This plan names no risk, so there is nothing to label. The checks below still apply.</p>\n");
        }
        for (Finding f : result.findings()) html.append(finding(f, result.reproduce()));

        html.append("<h2>The run</h2>\n<div class=\"stats\">\n");
        stat(html, "%,d".formatted(result.sent().ok()), "accepted (ok)");
        stat(html, "%,d".formatted(result.sent().fail()), "refused (fail)");
        stat(html, "%,d".formatted(result.sent().info()), "unknown (info)");
        stat(html, "%,d".formatted(result.drain().rows()), "rows in " + plan.correlateBy().table());
        if (result.faultsFired() != null) stat(html, result.faultsFired() + " of " + plan.fault().times(), "crashes fired");
        if (result.restarts() != null) stat(html, String.valueOf(result.restarts()), "app restarts");
        stat(html, "%.1fs".formatted(result.sent().millis() / 1000.0), "sending");
        stat(html, "%.1fs".formatted(result.drain().waitedMillis() / 1000.0), result.drain().settled() ? "draining" : "draining (gave up)");
        html.append("</div>\n");

        html.append("<h2>Checks</h2>\n<table>\n<thead><tr><th>Check</th><th>Result</th><th>Offenders</th></tr></thead>\n<tbody>\n");
        for (CheckResult c : result.checks()) {
            html.append("<tr><td class=\"mono\">").append(esc(c.name())).append("</td><td><span class=\"chip ")
                .append(c.passed() ? "pass\">PASS" : "fail\">FAIL").append("</span></td><td>");
            if (c.offenders().isEmpty()) html.append("<span class=\"muted\">none</span>");
            else html.append(ids(c.offenders()));
            html.append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n");

        html.append("<h2>The plan that ran</h2>\n<pre>").append(esc(planText.strip())).append("</pre>\n");
        html.append("""
            <footer>Also in this run folder: <span class="mono">history.jsonl</span> (every request and its outcome),
            <span class="mono">result.json</span>, <span class="mono">app.log</span> and <span class="mono">faults.jsonl</span>.</footer>
            </main>
            </body>
            </html>
            """);
        return html.toString();
    }

    private static String finding(Finding f, String reproduce) {
        String label = f.label().name().toLowerCase(Locale.ROOT).replace('_', '-');
        var html = new StringBuilder();
        html.append("<article class=\"card\">\n")
            .append("<span class=\"chip ").append(label).append("\">").append(label.replace('-', ' ')).append("</span>\n")
            .append("<h3>").append(esc(f.pattern())).append(" &middot; ").append(esc(f.title())).append("</h3>\n")
            .append("<div class=\"mono\">").append(esc(f.location())).append(" <span class=\"muted\">").append(esc(f.file())).append(':').append(f.line())
            .append("</span></div>\n<dl>\n");
        row(html, "Journey", esc(f.journey()));
        row(html, "The run", esc(f.reason()));
        row(html, "The scan", esc(f.why()) + " <span class=\"muted\">(" + esc(f.confidence()) + " confidence)</span>");
        if (!f.evidence().isEmpty()) row(html, "Evidence", ids(f.evidence()));
        html.append("</dl>\n");
        if (f.label() == Label.CONFIRMED) html.append("<div class=\"label\">Reproduce</div>\n<pre>").append(esc(reproduce)).append("</pre>\n");
        return html.append("</article>\n").toString();
    }

    private static void row(StringBuilder html, String term, String definition) {
        html.append("<dt>").append(term).append("</dt><dd>").append(definition).append("</dd>\n");
    }

    private static void stat(StringBuilder html, String number, String label) {
        html.append("<div class=\"stat\"><div class=\"n\">").append(esc(number)).append("</div><div class=\"l\">").append(esc(label)).append("</div></div>\n");
    }

    private static String ids(List<String> ids) {
        var html = new StringBuilder("<div class=\"ids\">");
        for (String id : ids.stream().limit(SHOW_IDS).toList()) html.append("<code>").append(esc(id)).append("</code>");
        if (ids.size() > SHOW_IDS) html.append("<span class=\"muted\">and ").append(ids.size() - SHOW_IDS).append(" more in result.json</span>");
        return html.append("</div>").toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = """
        :root{--bg:#f6f6f3;--panel:#fff;--text:#1c1c1a;--muted:#6d6d67;--line:#e2e2dc;--code:#efefea;
        --bad:#b42318;--bad-bg:#fdecea;--warn:#9a5b06;--warn-bg:#fdf1de;--good:#1d7444;--good-bg:#e6f4ec;--none:#5c5f63;--none-bg:#ebebe8}
        @media (prefers-color-scheme:dark){:root{--bg:#141413;--panel:#1c1c1a;--text:#ececea;--muted:#a4a49e;--line:#30302d;--code:#262624;
        --bad:#f97066;--bad-bg:#3b1b18;--warn:#f5b546;--warn-bg:#38290f;--good:#4cc98a;--good-bg:#12301f;--none:#b4b4ae;--none-bg:#2a2a28}}
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--text);font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif}
        main{max-width:960px;margin:0 auto;padding:32px 16px 64px}
        h1{font-size:28px;margin:4px 0}
        h2{font-size:17px;margin:36px 0 12px}
        h3{font-size:18px;margin:10px 0 2px}
        .eyebrow,.label{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
        .label{margin:16px 0 6px}
        .meta,.muted{color:var(--muted)}
        .meta{font-size:13px;overflow-wrap:anywhere}
        .mono,code,pre{font-family:ui-monospace,"Cascadia Code",Consolas,monospace}
        .mono{font-size:13.5px;overflow-wrap:anywhere}
        .verdict{margin-top:20px;padding:18px 20px;border-radius:12px;display:flex;gap:16px;align-items:center}
        .verdict.fail{background:var(--bad-bg)}
        .verdict.pass{background:var(--good-bg)}
        .verdict .big{font-size:21px;font-weight:650}
        .chip{display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;font-weight:650;text-transform:uppercase;letter-spacing:.04em;white-space:nowrap}
        .chip.fail,.chip.confirmed{background:var(--bad);color:var(--bg)}
        .chip.pass{background:var(--good);color:var(--bg)}
        .chip.suspected{background:var(--warn);color:var(--bg)}
        .chip.not-tested{background:var(--none-bg);color:var(--none)}
        .card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:20px;margin-bottom:16px}
        dl{display:grid;grid-template-columns:max-content 1fr;gap:8px 18px;margin:16px 0 0}
        dt{color:var(--muted)}
        dd{margin:0;min-width:0}
        .ids{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
        .ids code{background:var(--code);padding:1px 8px;border-radius:6px;font-size:12.5px}
        pre{background:var(--code);padding:14px 16px;border-radius:10px;overflow-x:auto;font-size:12.5px;line-height:1.5;margin:0}
        .stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px}
        .stat{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:12px 14px}
        .stat .n{font-size:20px;font-weight:650}
        .stat .l{color:var(--muted);font-size:12.5px}
        table{width:100%;border-collapse:separate;border-spacing:0;background:var(--panel);border:1px solid var(--line);border-radius:10px}
        th,td{text-align:left;padding:10px 14px;border-bottom:1px solid var(--line);vertical-align:top}
        tr:last-child td{border-bottom:0}
        th{font-size:12.5px;color:var(--muted);font-weight:600}
        footer{margin-top:36px;color:var(--muted);font-size:13px}
        @media (max-width:560px){dl{grid-template-columns:1fr;gap:2px}dd{margin-bottom:8px}.verdict{flex-direction:column;align-items:flex-start}}
        """;
}
