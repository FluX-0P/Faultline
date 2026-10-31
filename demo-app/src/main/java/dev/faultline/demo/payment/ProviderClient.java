package dev.faultline.demo.payment;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Charges each payment at the payment provider, over HTTP, trying until the provider says yes. */
@Component
class ProviderClient {
    private static final long FIRST_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 1_000;

    record Charge(String paymentId, String accountId, long amountMinor) {}

    /** null when no provider is set: payments are then not charged. */
    private final RestClient http;
    private final JsonMapper json;
    /** B5 when false: a charge that timed out may have gone through, and trying it again charges the payment twice. */
    private final boolean idempotencyKey;
    /** B6 when false: a failed charge is tried again at once, as often as it takes. */
    private final boolean backoff;

    ProviderClient(@Value("${payments.provider.url:}") String url, @Value("${payments.provider.timeout:1s}") Duration timeout,
                   @Value("${payments.provider.idempotency-key:none}") String key, @Value("${payments.provider.retry:immediately}") String retry,
                   JsonMapper json) {
        this.json = json;
        this.idempotencyKey = key.equals("payment-id");
        this.backoff = retry.equals("with-backoff");
        if (url.isBlank()) {
            this.http = null;
        } else {
            var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
            factory.setReadTimeout(timeout);
            this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        }
    }

    /** Called inside the payment's transaction. */
    void charge(PaymentEvent p) {
        if (http == null) return;
        String body = json.writeValueAsString(new Charge(p.paymentId(), p.accountId(), p.amountMinor()));
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("stopped while charging " + p.paymentId());
            try {
                var request = http.post().uri("/charges").contentType(MediaType.APPLICATION_JSON).body(body);
                if (idempotencyKey) request = request.header("Idempotency-Key", p.paymentId());
                request.retrieve().toBodilessEntity();
                return;
            } catch (ResourceAccessException | HttpServerErrorException e) {
                // timed out or refused, or a 5xx: try again. B6 without a backoff: at once and without end, so a
                // provider that is coming back from an outage is buried under the retries of every worker
                if (backoff) pause(attempt);
            }
        }
    }

    /** 100ms, 200ms, 400ms ... up to 1s, with jitter so workers don't retry in step. */
    private static void pause(int attempt) {
        long ceiling = Math.min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS << Math.min(attempt - 1, 10));
        try {
            Thread.sleep(ceiling / 2 + ThreadLocalRandom.current().nextLong(ceiling / 2 + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
