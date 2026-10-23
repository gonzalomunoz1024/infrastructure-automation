package com.iaf.console.identity.iam.adapters.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.application.GuardrailCommands;
import com.iaf.console.identity.iam.application.GuardrailInput;
import com.iaf.console.identity.iam.application.GuardrailQueries;
import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/guardrails} (the internal principal's) and {@code /v1/accounts/{accountId}/guardrails} (the ones that apply there). */
@RestController
public class GuardrailsController {

    private final GuardrailCommands commands;
    private final GuardrailQueries queries;

    public GuardrailsController(GuardrailCommands commands, GuardrailQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/guardrails")
    public Mono<Page<Guardrail>> list(Caller caller, @RequestParam(required = false) Integer limit, @RequestParam(required = false) String after) {
        return queries.list(caller, Paging.limit(limit), after);
    }

    @PostMapping("/v1/guardrails")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Guardrail> create(Caller caller, @RequestBody GuardrailInput body) {
        return commands.create(caller, body);
    }

    @GetMapping("/v1/guardrails/{guardrailId}")
    public Mono<Guardrail> get(Caller caller, @PathVariable String guardrailId) {
        return queries.get(caller, guardrailId);
    }

    @PatchMapping("/v1/guardrails/{guardrailId}")
    public Mono<Guardrail> update(Caller caller, @PathVariable String guardrailId, @RequestBody GuardrailInput body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return commands.update(caller, guardrailId, body, Versions.ifMatch(ifMatch));
    }

    @DeleteMapping("/v1/guardrails/{guardrailId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(Caller caller, @PathVariable String guardrailId) {
        return commands.delete(caller, guardrailId);
    }

    @GetMapping("/v1/accounts/{accountId}/guardrails")
    public Mono<Map<String, List<Guardrail>>> forAccount(Caller caller, @PathVariable String accountId) {
        return queries.forAccount(caller, accountId).map(items -> Map.of("items", items));
    }
}
