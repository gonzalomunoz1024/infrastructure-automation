package com.iaf.console.bff.session.application;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The OpenID Connect provider the console signs in with: its issuer (discovery is read from
 * {@code <issuer>/.well-known/openid-configuration}), the client, the redirect URI the provider
 * knows, the scopes, and the claim the groups come in.
 */
@ConfigurationProperties(prefix = "console.oidc")
public record OidcProperties(
        @DefaultValue("") String issuer,
        @DefaultValue("console") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("http://localhost:18081/auth/callback") String redirectUri,
        @DefaultValue({"openid", "profile", "email", "groups"}) List<String> scopes,
        @DefaultValue("groups") String groupsClaim,
        @DefaultValue("30s") java.time.Duration clockSkew) {
}
