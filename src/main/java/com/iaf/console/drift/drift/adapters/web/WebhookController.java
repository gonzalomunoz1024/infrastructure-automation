package com.iaf.console.drift.drift.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.drift.drift.application.WebhookService;

import reactor.core.publisher.Mono;

/**
 * A provider's change notifications. The console's JWT does not apply here (the authentication
 * filter lets the path through): the provider proves itself with {@code X-Webhook-Signature}.
 */
@RestController
public final class WebhookController {

    static final String SIGNATURE = "X-Webhook-Signature";

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/v1/webhooks/{provider}")
    public Mono<ResponseEntity<WebhookService.Scheduled>> receive(@PathVariable String provider,
            @RequestHeader(value = SIGNATURE, required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        return webhooks.receive(provider, signature, body == null ? new byte[0] : body)
                .map(scheduled -> ResponseEntity.status(HttpStatus.ACCEPTED).body(scheduled));
    }
}
