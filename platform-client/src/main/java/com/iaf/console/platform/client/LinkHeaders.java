package com.iaf.console.platform.client;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Reads the P-05 {@code Link: <url>; rel="next"} header and the {@code after} cursor in it. */
public final class LinkHeaders {

    private LinkHeaders() {
    }

    /** The {@code after} cursor of the {@code rel="next"} link, when the header carries one. */
    public static Optional<String> nextCursor(List<String> linkHeaders) {
        if (linkHeaders == null) {
            return Optional.empty();
        }
        for (String header : linkHeaders) {
            for (String link : header.split(",")) {
                String trimmed = link.trim();
                if (!trimmed.contains("rel=\"next\"") && !trimmed.contains("rel=next")) {
                    continue;
                }
                int open = trimmed.indexOf('<');
                int close = trimmed.indexOf('>');
                if (open < 0 || close < open) {
                    continue;
                }
                String url = trimmed.substring(open + 1, close);
                Optional<String> after = queryParam(url, "after");
                if (after.isPresent()) {
                    return after;
                }
            }
        }
        return Optional.empty();
    }

    /** The {@code rel="next"} URL itself. */
    public static Optional<String> nextUrl(List<String> linkHeaders) {
        if (linkHeaders == null) {
            return Optional.empty();
        }
        for (String header : linkHeaders) {
            for (String link : header.split(",")) {
                String trimmed = link.trim();
                if (trimmed.contains("rel=\"next\"") || trimmed.contains("rel=next")) {
                    int open = trimmed.indexOf('<');
                    int close = trimmed.indexOf('>');
                    if (open >= 0 && close > open) {
                        return Optional.of(trimmed.substring(open + 1, close));
                    }
                }
            }
        }
        return Optional.empty();
    }

    static Optional<String> queryParam(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) {
            return Optional.empty();
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                return Optional.of(URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    /** A {@code Link} header value for the next page of a URL, for servers (the fake) that answer one. */
    public static String next(String urlWithoutCursor, String cursor) {
        String joiner = urlWithoutCursor.contains("?") ? "&" : "?";
        return "<" + urlWithoutCursor + joiner + "after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) + ">; rel=\"next\"";
    }
}
