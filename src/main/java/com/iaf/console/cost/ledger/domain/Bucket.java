package com.iaf.console.cost.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the ledger adds up by: an account, region, service, type and the exact set of tags. Every
 * resource with the same five falls into one bucket, so a report is bounded by the number of
 * distinct tag sets, never by the number of resources. The id is a hash of the five.
 */
public record Bucket(String id, String accountId, String region, String service, String type, Map<String, String> tags) {

    public Bucket {
        Objects.requireNonNull(accountId, "accountId");
        region = region == null ? "" : region;
        service = service == null ? "" : service;
        type = type == null ? "" : type;
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        if (id == null) {
            id = idOf(accountId, region, service, type, tags);
        }
    }

    public static Bucket of(String accountId, String region, String service, String type, Map<String, String> tags) {
        return new Bucket(null, accountId, region, service, type, tags);
    }

    public static Bucket of(ResourceSnapshot r) {
        return of(r.accountId(), r.region(), r.service(), r.type(), r.tags());
    }

    /** The value this bucket groups under for a dimension: service, type, region, account or tag:key ({@code null} when the tag is absent). */
    public String dimension(String groupBy) {
        if (groupBy == null) {
            return null;
        }
        return switch (groupBy) {
            case "service" -> service;
            case "type" -> type;
            case "region" -> region;
            case "account" -> accountId;
            default -> groupBy.startsWith("tag:") ? tags.get(groupBy.substring(4)) : null;
        };
    }

    static String idOf(String accountId, String region, String service, String type, Map<String, String> tags) {
        StringBuilder canonical = new StringBuilder().append(accountId).append('\u0000').append(region).append('\u0000').append(service)
                .append('\u0000').append(type);
        new TreeMap<>(tags).forEach((k, v) -> canonical.append('\u0000').append(k).append('=').append(v));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
