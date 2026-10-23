package com.iaf.console.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A stand-in for the BFF on a free local port: routes by method and raw path, and records every request. */
final class StubBff implements AutoCloseable {

    record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        Map<String, String> form() {
            return decode(body);
        }

        Map<String, String> params() {
            return decode(query);
        }
    }

    record Reply(int status, Map<String, String> headers, String body) {
        static Reply json(int status, String json) {
            return new Reply(status, Map.of("Content-Type", "application/json"), json);
        }

        static Reply text(int status, String contentType, String body) {
            return new Reply(status, Map.of("Content-Type", contentType), body);
        }

        static Reply empty(int status) {
            return new Reply(status, Map.of(), "");
        }

        Reply with(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, h, body);
        }
    }

    @FunctionalInterface
    interface Route {
        Reply handle(Request request) throws Exception;
    }

    /** Answers each reply in turn, then the last one again. */
    static Route sequence(Reply... replies) {
        AtomicInteger next = new AtomicInteger();
        return request -> replies[Math.min(next.getAndIncrement(), replies.length - 1)];
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private StubBff() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::serve);
        server.setExecutor(executor);
        server.start();
    }

    static StubBff start() throws IOException {
        return new StubBff();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    StubBff on(String method, String path, Route route) {
        routes.put(method + " " + path, route);
        return this;
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    List<Request> requests(String method, String path) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    void reset() {
        routes.clear();
        requests.clear();
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), headers, body);
            requests.add(request);
            Route route = routes.get(request.method() + " " + request.path());
            Reply reply;
            try {
                reply = route == null
                        ? Reply.json(404, "{\"error\":\"stub.no-route\",\"message\":\"no route for " + request.method() + " " + request.path() + "\"}")
                        : route.handle(request);
            } catch (Exception e) {
                reply = Reply.json(500, "{\"error\":\"stub.failed\",\"message\":\"" + e + "\"}");
            }
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    static Map<String, String> decode(String encoded) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) return fields;
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            fields.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return fields;
    }

    /** The address of a local port nothing listens on. */
    static String closedEndpoint() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    /** Every value of a repeated query parameter. */
    static List<String> all(String query, String name) {
        List<String> values = new ArrayList<>();
        if (query == null) return values;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                values.add(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }
}
