package com.iaf.console.bff.digests.adapters.inbound;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.digests.application.Digests;
import com.iaf.console.bff.digests.domain.Digest;
import com.iaf.console.bff.digests.domain.DigestSummary;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** The session account's weekly digests (decision 32.4): {@code /console/v1/digests} and one week's, by its Monday. */
@RestController
public class DigestsController {

    static final String DIGESTS = "/console/v1/digests";

    private final Digests digests;

    public DigestsController(Digests digests) {
        this.digests = digests;
    }

    @GetMapping(path = DIGESTS, produces = "application/json")
    public Mono<Page<DigestSummary>> list(@RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> digests.list(session, limit, after));
    }

    @GetMapping(path = DIGESTS + "/{weekStart}", produces = "application/json")
    public Mono<Digest> get(@PathVariable("weekStart") String weekStart, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> digests.get(session, weekStart));
    }
}
