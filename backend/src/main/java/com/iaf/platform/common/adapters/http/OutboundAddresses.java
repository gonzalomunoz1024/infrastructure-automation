package com.iaf.platform.common.adapters.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.http.client.HttpClientResponse;

/**
 * Where calls to provider-supplied URLs may not go: link-local addresses (169.254.0.0/16 and
 * fe80::/10, home of the cloud metadata services), the other well-known metadata hosts and the
 * unspecified address. Private and loopback addresses stay reachable because on-prem providers
 * live there. A URL is checked before the call, every address DNS returns is checked before the
 * connection, and a redirect is only followed when its target passes the same check.
 */
public final class OutboundAddresses {

    private static final Set<String> METADATA_HOSTS = Set.of("metadata", "metadata.google.internal", "metadata.goog",
            "instance-data", "instance-data.ec2.internal");
    private static final Set<InetAddress> METADATA_ADDRESSES = Set.of(literal("100.100.100.200"), literal("fd00:ec2::254"));
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    /** Resolves names with the JDK resolver and refuses a name when any of its addresses is refused. */
    public static final AddressResolverGroup<InetSocketAddress> RESOLVER = new AddressResolverGroup<>() {
        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new GuardedNameResolver(executor).asAddressResolver();
        }
    };

    private OutboundAddresses() {
    }

    /** The client with the guarded resolver and redirects followed only to allowed hosts. */
    public static HttpClient guard(HttpClient http) {
        return http.resolver(RESOLVER).followRedirect(OutboundAddresses::followRedirect);
    }

    public static boolean blocked(InetAddress address) {
        return address.isLinkLocalAddress() || address.isAnyLocalAddress() || METADATA_ADDRESSES.contains(address);
    }

    /** The URI itself, or an {@link IllegalArgumentException} when its host is a metadata name or a refused literal address. */
    public static URI requireAllowed(URI uri) {
        String host = uri.getHost() == null ? "" : normalized(uri.getHost());
        if (METADATA_HOSTS.contains(host)) {
            throw new IllegalArgumentException(uri.getHost() + " is a cloud metadata host; providers cannot call it");
        }
        if (NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host)) {
            InetAddress address = literal(host);
            if (blocked(address)) {
                throw new IllegalArgumentException(uri.getHost() + " is a link-local or cloud metadata address; providers cannot call it");
            }
        }
        return uri;
    }

    static boolean followRedirect(HttpClientRequest request, HttpClientResponse response) {
        if (!REDIRECTS.contains(response.status().code())) {
            return false;
        }
        String location = response.responseHeaders().get(HttpHeaderNames.LOCATION);
        if (location == null || location.isBlank()) {
            return false;
        }
        try {
            requireAllowed(URI.create(request.resourceUrl()).resolve(location.trim()));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalized(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    private static InetAddress literal(String ip) {
        try {
            return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(ip));
        } catch (UnknownHostException | NullPointerException e) {
            throw new IllegalArgumentException("Not an IP address: " + ip, e);
        }
    }

    static final class GuardedNameResolver extends InetNameResolver {

        GuardedNameResolver(EventExecutor executor) {
            super(executor);
        }

        @Override
        protected void doResolve(String host, Promise<InetAddress> promise) {
            try {
                promise.setSuccess(allowed(host).get(0));
            } catch (UnknownHostException e) {
                promise.setFailure(e);
            }
        }

        @Override
        protected void doResolveAll(String host, Promise<List<InetAddress>> promise) {
            try {
                promise.setSuccess(allowed(host));
            } catch (UnknownHostException e) {
                promise.setFailure(e);
            }
        }

        static List<InetAddress> allowed(String host) throws UnknownHostException {
            if (METADATA_HOSTS.contains(normalized(host))) {
                throw new UnknownHostException(host + " is a cloud metadata host; providers cannot call it");
            }
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress address : all) {
                if (blocked(address)) {
                    throw new UnknownHostException(host + " resolves to " + address.getHostAddress() + ", a link-local or cloud metadata address; providers cannot call it");
                }
            }
            return List.of(all);
        }
    }
}
