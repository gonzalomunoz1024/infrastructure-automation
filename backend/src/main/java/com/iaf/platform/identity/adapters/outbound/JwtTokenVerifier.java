package com.iaf.platform.identity.adapters.outbound;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.ports.outbound.TokenVerifier;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import lombok.extern.slf4j.Slf4j;

/**
 * OIDC: validates a JWT against the issuer's JWKS and maps its claims to an identity
 * ({@code sub}, {@code name}, {@code email}, {@code groups} -> teams, {@code roles}).
 */
@Slf4j
@Component
@Profile("oidc")
public class JwtTokenVerifier implements TokenVerifier {

    private final DefaultJWTProcessor<SecurityContext> processor;

    public JwtTokenVerifier(PlatformProperties props) throws Exception {
        this(JWKSourceBuilder.<SecurityContext>create(new URL(props.getIdentity().getOidcJwksUri())).build(),
                props.getIdentity().getOidcIssuer(), props.getIdentity().getOidcAudience());
    }

    JwtTokenVerifier(JWKSource<SecurityContext> keys, String issuer, String audience) {
        processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        JWTClaimsSet.Builder expected = new JWTClaimsSet.Builder();
        if (issuer != null && !issuer.isBlank()) {
            expected.issuer(issuer);
        }
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                audience == null || audience.isBlank() ? null : audience, expected.build(), java.util.Set.of("sub")));
    }

    @Override
    public Optional<Identity> verify(String token) {
        try {
            JWTClaimsSet claims = processor.process(token, null);
            List<String> teams = strings(claims, "groups");
            List<String> roles = strings(claims, "roles");
            String name = claims.getStringClaim("name") == null ? claims.getSubject() : claims.getStringClaim("name");
            return Optional.of(new Identity(claims.getSubject(), name, claims.getStringClaim("email"), teams, roles,
                    roles.contains("admin"), Identity.Kind.USER));
        } catch (Exception e) {
            log.debug("rejected token: {}", e.toString());
            return Optional.empty();
        }
    }

    private static List<String> strings(JWTClaimsSet claims, String name) {
        try {
            List<String> v = claims.getStringListClaim(name);
            return v == null ? new ArrayList<>() : v;
        } catch (java.text.ParseException e) {
            return new ArrayList<>();
        }
    }
}
