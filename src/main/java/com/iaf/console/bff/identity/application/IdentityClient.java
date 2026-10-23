package com.iaf.console.bff.identity.application;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;

/**
 * The one way the BFF calls the identity service: a WebClient at its URL, with an internal JWT
 * minted for the call, either for the BFF itself ({@link #asService()}) or for a session's user in
 * their account ({@link #asUser(Session)}). Every adapter that talks to identity goes through here,
 * so the token rules live in one place.
 */
public class IdentityClient {

    private final WebClient.Builder builder;
    private final Supplier<String> baseUrl;
    private final Trust trust;
    private final IdentityProperties properties;
    private volatile WebClient client;

    public IdentityClient(WebClient.Builder builder, Supplier<String> baseUrl, Trust trust, IdentityProperties properties) {
        this.builder = builder;
        this.baseUrl = baseUrl;
        this.trust = trust;
        this.properties = properties;
    }

    public String serviceName() {
        return properties.serviceName();
    }

    public Duration timeout() {
        return properties.timeout();
    }

    /** The identity service's URL, resolved on first use (the demo stand-in needs the server's port). */
    public String url() {
        return baseUrl.get();
    }

    /** A request as the BFF itself: the console-internal principal. */
    public WebClient.RequestBodyUriSpec asService(org.springframework.http.HttpMethod method) {
        String token = trust.mint(InternalToken.service(properties.serviceName(), trust.issuer()));
        WebClient.RequestBodyUriSpec spec = client().method(method);
        spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return spec;
    }

    /** A request as a session's user in the session's account and region. */
    public WebClient.RequestBodyUriSpec asUser(org.springframework.http.HttpMethod method, Session session) {
        String token = trust.mint(InternalToken.user(properties.serviceName(), session.user().userId(), session.accountId(), session.region(),
                session.currentAccount().groups(), session.currentAccount().roles(), session.rolesUntil()));
        WebClient.RequestBodyUriSpec spec = client().method(method);
        spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return spec;
    }

    /** The token alone, for callers that build their own request. */
    public String serviceToken() {
        return trust.mint(InternalToken.service(properties.serviceName(), trust.issuer()));
    }

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = builder.clone().baseUrl(baseUrl.get()).build();
                    client = c;
                }
            }
        }
        return c;
    }
}
