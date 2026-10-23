package com.iaf.console.bff.trust.adapters.inbound;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.bff.trust.application.Trust;

/** The BFF's public keys, which the platform (P-02) and the console services verify against. */
@RestController
public class JwksController {

    private final Trust trust;

    public JwksController(Trust trust) {
        this.trust = trust;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic()).body(trust.jwks().toJSONObject(true));
    }
}
