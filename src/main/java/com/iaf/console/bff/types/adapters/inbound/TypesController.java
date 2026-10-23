package com.iaf.console.bff.types.adapters.inbound;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;

import reactor.core.publisher.Mono;

/** {@code GET /console/v1/types} and {@code /types/{key}}: the descriptors, with ETag and If-None-Match. */
@RestController
public class TypesController {

    private final Types types;

    public TypesController(Types types) {
        this.types = types;
    }

    @GetMapping(path = "/console/v1/types", produces = "application/json")
    public Mono<ResponseEntity<Map<String, List<ConsoleType>>>> list(
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(types::list).map(list -> {
            String etag = Types.etagOf(list);
            if (matches(ifNoneMatch, etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).<Map<String, List<ConsoleType>>>build();
            }
            return ResponseEntity.ok().eTag(etag).header("Cache-Control", "private, no-cache").body(Map.of("items", list));
        });
    }

    @GetMapping(path = "/console/v1/types/{service}/{type}", produces = "application/json")
    public Mono<ResponseEntity<ConsoleType>> get(@PathVariable("service") String service, @PathVariable("type") String type,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> types.get(session, service + "/" + type)).map(found -> {
            String etag = Types.etagOf(List.of(found));
            if (matches(ifNoneMatch, etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).<ConsoleType>build();
            }
            return ResponseEntity.ok().eTag(etag).header("Cache-Control", "private, no-cache").body(found.withEtag(etag));
        });
    }

    /** The key percent-encoded as one segment ({@code compute%2Fvm}), as the contract spells it. */
    @GetMapping(path = "/console/v1/types/{key}", produces = "application/json")
    public Mono<ResponseEntity<ConsoleType>> getEncoded(@PathVariable("key") String key,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch, ServerWebExchange exchange) {
        // the path variable keeps a percent-encoded slash; type keys hold no other percent signs
        key = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);
        int slash = key.indexOf('/');
        if (slash <= 0) {
            return Mono.error(new IllegalArgumentException("a type key is <service>/<type>"));
        }
        return get(key.substring(0, slash), key.substring(slash + 1), ifNoneMatch, exchange);
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.equals("*") || c.equals(etag) || c.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }
}
