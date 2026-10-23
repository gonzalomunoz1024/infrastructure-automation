package com.iaf.console.bff.preferences.adapters.inbound;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.preferences.application.PreferencesService;
import com.iaf.console.bff.preferences.domain.Preferences;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/** {@code /console/v1/preferences}: the user's preferences, read and replaced. */
@RestController
public class PreferencesController {

    private final PreferencesService preferences;

    public PreferencesController(PreferencesService preferences) {
        this.preferences = preferences;
    }

    @GetMapping(path = "/console/v1/preferences", produces = "application/json")
    public Mono<Preferences> get(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> preferences.get(session.user().userId()));
    }

    @PutMapping(path = "/console/v1/preferences", consumes = "application/json", produces = "application/json")
    public Mono<Preferences> put(@RequestBody Preferences body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> preferences.replace(session.user().userId(), body));
    }
}
