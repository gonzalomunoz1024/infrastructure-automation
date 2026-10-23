package com.iaf.console.cost.chargeback.adapters.finance;

import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.cost.chargeback.ports.outbound.FinanceExportPort;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Finance's export as one HTTP POST per account and month: the CSV, with the month, account and
 * sha256 in headers, and {@code Idempotency-Key} {@code chargeback:<account>:<month>}, so a
 * second delivery after a lost answer is recognised by the receiver.
 */
public final class WebhookFinanceExport implements FinanceExportPort {

    private final WebClient web;
    private final String path;

    public WebhookFinanceExport(WebClient web, String path) {
        this.web = web;
        this.path = path;
    }

    @Override
    public Mono<Void> deliver(ChargebackMonth month, String csv) {
        return web.post().uri(path).contentType(new MediaType("text", "csv")).header("Idempotency-Key", "chargeback:" + month.id())
                .header("X-Chargeback-Month", month.month()).header("X-Chargeback-Account", month.accountId())
                .header("X-Content-Sha256", month.sha256()).bodyValue(csv)
                .retrieve().toBodilessEntity().then()
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)));
    }

    @Override
    public String kind() {
        return "webhook";
    }
}
