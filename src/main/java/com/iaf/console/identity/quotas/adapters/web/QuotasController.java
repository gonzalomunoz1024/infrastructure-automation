package com.iaf.console.identity.quotas.adapters.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.quotas.application.QuotaCommands;
import com.iaf.console.identity.quotas.application.QuotaQueries;
import com.iaf.console.identity.quotas.application.RequestIncrease;
import com.iaf.console.identity.quotas.application.SetQuota;
import com.iaf.console.identity.quotas.domain.Quota;
import com.iaf.console.identity.quotas.domain.QuotaRequest;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts/{accountId}/quotas}. */
@RestController
public class QuotasController {

    private final QuotaCommands commands;
    private final QuotaQueries queries;

    public QuotasController(QuotaCommands commands, QuotaQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts/{accountId}/quotas")
    public Mono<Map<String, List<Quota>>> list(Caller caller, @PathVariable String accountId) {
        return queries.list(caller, accountId).map(items -> Map.of("items", items));
    }

    @GetMapping("/v1/accounts/{accountId}/quotas/{quotaKey}")
    public Mono<Quota> get(Caller caller, @PathVariable String accountId, @PathVariable String quotaKey) {
        return queries.get(caller, accountId, quotaKey);
    }

    @PutMapping("/v1/accounts/{accountId}/quotas/{quotaKey}")
    public Mono<Quota> set(Caller caller, @PathVariable String accountId, @PathVariable String quotaKey, @RequestBody SetQuota body) {
        return commands.set(caller, accountId, quotaKey, body);
    }

    @PostMapping("/v1/accounts/{accountId}/quotas/{quotaKey}:request-increase")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<QuotaRequest> requestIncrease(Caller caller, @PathVariable String accountId, @PathVariable String quotaKey,
            @RequestBody RequestIncrease body) {
        return commands.requestIncrease(caller, accountId, quotaKey, body);
    }

    @GetMapping("/v1/accounts/{accountId}/quota-requests/{requestId}")
    public Mono<QuotaRequest> getRequest(Caller caller, @PathVariable String accountId, @PathVariable String requestId) {
        return queries.getRequest(caller, accountId, requestId);
    }

    @DeleteMapping("/v1/accounts/{accountId}/quota-requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> withdraw(Caller caller, @PathVariable String accountId, @PathVariable String requestId) {
        return commands.withdraw(caller, accountId, requestId);
    }
}
