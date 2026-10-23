package com.iaf.console.bff.clients.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import com.iaf.console.bff.clients.domain.RequestedFrom;

/** Where a CLI called from, behind none, one or two proxies, and the bearer header read in any case. */
class RequestOriginTest {

    private static final InetSocketAddress INGRESS = new InetSocketAddress("10.1.0.9", 443);

    @Test
    void withoutProxiesTheConnectionsPeerIsTheClient() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/device").remoteAddress(INGRESS).header("X-Forwarded-For", "6.6.6.6")
                .header(HttpHeaders.USER_AGENT, "console-cli/1.0").build();
        RequestedFrom from = RequestOrigin.of(request, 0);
        assertEquals("10.1.0.9", from.ip(), "a client's own X-Forwarded-For counts for nothing");
        assertEquals("console-cli/1.0", from.userAgent());
    }

    @Test
    void behindProxiesTheClientIsAsManyEntriesFromTheRight() {
        MockServerHttpRequest oneProxy = MockServerHttpRequest.post("/auth/device").remoteAddress(INGRESS).header("X-Forwarded-For", "6.6.6.6, 203.0.113.5")
                .build();
        assertEquals("203.0.113.5", RequestOrigin.address(oneProxy, 1), "what the client wrote is left of what the ingress added");
        MockServerHttpRequest twoProxies = MockServerHttpRequest.post("/auth/device").remoteAddress(INGRESS)
                .header("X-Forwarded-For", "6.6.6.6, 203.0.113.5").header("X-Forwarded-For", "198.51.100.2").build();
        assertEquals("203.0.113.5", RequestOrigin.address(twoProxies, 2));
        MockServerHttpRequest none = MockServerHttpRequest.post("/auth/device").remoteAddress(INGRESS).build();
        assertEquals("10.1.0.9", RequestOrigin.address(none, 1));
        assertEquals("6.6.6.6", RequestOrigin.address(MockServerHttpRequest.post("/").remoteAddress(INGRESS).header("X-Forwarded-For", "6.6.6.6").build(), 3));
    }

    @Test
    void theBearerSchemeIsReadInAnyCaseAndAnotherSchemeIsNoBearer() {
        assertEquals("abc", BearerWebFilter.bearerToken(MockServerHttpRequest.get("/").header(HttpHeaders.AUTHORIZATION, "Bearer abc").build()));
        assertEquals("abc", BearerWebFilter.bearerToken(MockServerHttpRequest.get("/").header(HttpHeaders.AUTHORIZATION, "bearer  abc ").build()));
        assertEquals("", BearerWebFilter.bearerToken(MockServerHttpRequest.get("/").header(HttpHeaders.AUTHORIZATION, "Bearer").build()));
        assertNull(BearerWebFilter.bearerToken(MockServerHttpRequest.get("/").header(HttpHeaders.AUTHORIZATION, "Basic YTpi").build()));
        assertNull(BearerWebFilter.bearerToken(MockServerHttpRequest.get("/").header(HttpHeaders.AUTHORIZATION, "Bearerabc").build()));
        assertNull(BearerWebFilter.bearerToken(MockServerHttpRequest.get("/").build()));
    }
}
