package com.iaf.console.bff.platform.adapters.inbound;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.platform.application.Regions;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/** {@code GET /console/v1/regions}: the regions with what each offers. */
@RestController
public class RegionsController {

    /** A region as the contract lists it. */
    public record RegionView(String name, String title, String status, List<String> environments, List<String> types) {
    }

    private final Regions regions;

    public RegionsController(Regions regions) {
        this.regions = regions;
    }

    @GetMapping(path = "/console/v1/regions", produces = "application/json")
    public Mono<Map<String, List<RegionView>>> list(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).then(regions.all())
                .map(all -> Map.of("items", all.stream().map(r -> new RegionView(r.name(), r.title(), r.status(), r.environments(), r.types())).toList()));
    }
}
