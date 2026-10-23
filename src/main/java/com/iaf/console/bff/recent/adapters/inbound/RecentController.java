package com.iaf.console.bff.recent.adapters.inbound;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.recent.application.RecentPages;
import com.iaf.console.bff.recent.domain.Favourite;
import com.iaf.console.bff.recent.domain.RecentItem;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/**
 * The person's recent pages and favourite resources (decision 32.3): {@code /console/v1/recent}
 * and {@code /console/v1/favourites}, the wfrn of a favourite percent-encoded in the path.
 */
@RestController
public class RecentController {

    static final String RECENT = "/console/v1/recent";
    static final String FAVOURITES = "/console/v1/favourites";

    private final RecentPages pages;

    public RecentController(RecentPages pages) {
        this.pages = pages;
    }

    @GetMapping(path = RECENT, produces = "application/json")
    public Mono<RecentPages.Items<RecentItem>> recent(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(pages::recent);
    }

    @PostMapping(path = RECENT)
    public Mono<ResponseEntity<Void>> visit(@RequestBody(required = false) JsonNode body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> pages.visit(session, body)).then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }

    @GetMapping(path = FAVOURITES, produces = "application/json")
    public Mono<RecentPages.Items<Favourite>> favourites(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(pages::favourites);
    }

    @PutMapping(path = FAVOURITES + "/{wfrn}", produces = "application/json")
    public Mono<Favourite> put(@PathVariable("wfrn") String wfrn, @RequestBody(required = false) JsonNode body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> pages.putFavourite(session, wfrn, body));
    }

    @DeleteMapping(path = FAVOURITES + "/{wfrn}")
    public Mono<ResponseEntity<Void>> remove(@PathVariable("wfrn") String wfrn, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> pages.removeFavourite(session, wfrn))
                .then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }
}
