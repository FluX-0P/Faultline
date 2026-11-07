package dev.faultline.cli.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TracesTest {
    @Test
    void aRequestsTraceIdComesFromItsRequestIdAlone() {
        String id = Traces.traceId("pay-42-299");

        assertThat(id).hasSize(32).matches("[0-9a-f]{32}").isEqualTo(Traces.traceId("pay-42-299")).isNotEqualTo(Traces.traceId("pay-42-300"));
        assertThat(Traces.traceparent("pay-42-299")).matches("00-" + id + "-[0-9a-f]{16}-01");
    }

    @Test
    void theLinkOpensTheTraceInGrafana() {
        String link = Traces.link("pay-42-299");

        assertThat(link).startsWith("http://localhost:3300/explore?left=");
        assertThat(URLDecoder.decode(link.substring(link.indexOf('=') + 1), StandardCharsets.UTF_8))
            .contains("\"uid\":\"tempo\"").contains("\"query\":\"" + Traces.traceId("pay-42-299") + "\"");
    }
}
