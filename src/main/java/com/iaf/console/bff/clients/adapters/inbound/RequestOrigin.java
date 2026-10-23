package com.iaf.console.bff.clients.adapters.inbound;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

import com.iaf.console.bff.clients.domain.RequestedFrom;

/**
 * Where a request came from: the client's address and its {@code User-Agent}. Behind
 * {@code proxyHops} proxies that each add the address they got the request from to
 * {@code X-Forwarded-For}, the client's is that many entries from the right, the ones further
 * left being whatever the client wrote; with none, it is the connection's own peer.
 */
final class RequestOrigin {

    private RequestOrigin() {
    }

    static RequestedFrom of(ServerHttpRequest request, int proxyHops) {
        return new RequestedFrom(address(request, proxyHops), request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
    }

    static String address(ServerHttpRequest request, int proxyHops) {
        String peer = peer(request.getRemoteAddress());
        if (proxyHops <= 0) {
            return peer;
        }
        List<String> forwarded = new ArrayList<>();
        for (String header : request.getHeaders().getOrEmpty("X-Forwarded-For")) {
            for (String entry : header.split(",")) {
                if (!entry.isBlank()) {
                    forwarded.add(entry.strip());
                }
            }
        }
        if (forwarded.isEmpty()) {
            return peer;
        }
        // fewer entries than proxies means one of them added none: the leftmost is the best there is
        return forwarded.get(Math.max(0, forwarded.size() - proxyHops));
    }

    private static String peer(InetSocketAddress remote) {
        if (remote == null) {
            return null;
        }
        return remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
    }
}
