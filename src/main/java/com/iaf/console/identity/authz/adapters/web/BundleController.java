package com.iaf.console.identity.authz.adapters.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.authz.application.Bundles;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/** {@code GET /internal/opa/bundles/console-authz.tar.gz}: the console-internal principal or OPA with the bundle token; a user answers 404. */
@RestController
public class BundleController {

    private final Bundles bundles;

    public BundleController(Bundles bundles) {
        this.bundles = bundles;
    }

    @GetMapping("/internal/opa/bundles/console-authz.tar.gz")
    public Mono<ResponseEntity<byte[]>> bundle(Caller caller, @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        return bundles.current().map(bundle -> {
            if (ifNoneMatch != null && matches(ifNoneMatch, bundle.etag())) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(bundle.etag()).<byte[]>build();
            }
            return ResponseEntity.ok().eTag(bundle.etag()).contentType(MediaType.parseMediaType("application/gzip")).body(bundle.bytes());
        });
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals(etag) || c.equals(etag.replace("\"", "")) || "*".equals(c)) {
                return true;
            }
        }
        return false;
    }
}
