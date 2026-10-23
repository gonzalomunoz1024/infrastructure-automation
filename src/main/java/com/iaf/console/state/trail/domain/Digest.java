package com.iaf.console.state.trail.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.state.inventory.domain.CanonicalJson;
import com.iaf.console.state.inventory.domain.Json;

/**
 * The seal of one account's events in one period, the CloudTrail digest file of the console:
 * how many events there were, the first and the last, {@code root} (the SHA-256 over each event's
 * hash followed by a newline, in the log's time order), and {@code previous}, the hash of the
 * account's digest before it, so the digests form a chain of their own. {@code hash} covers all
 * of that ({@link #body}); {@code signature} is ES256 over the hash, by the key {@code keyId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Digest(String id, String accountId, String region, Instant from, Instant to, int count, String firstEvent, String lastEvent,
        String root, String previous, String hash, String keyId, String signature, Instant signedAt) {

    public static String idOf(String accountId, String region, Instant from) {
        return accountId + ":" + region + ":" + from;
    }

    /** What the digest's hash covers. */
    public ObjectNode body() {
        ObjectNode b = Json.object();
        b.put("id", id);
        b.put("accountId", accountId);
        b.put("region", region);
        b.put("from", from.toString());
        b.put("to", to.toString());
        b.put("count", count);
        Json.putIf(b, "firstEvent", firstEvent);
        Json.putIf(b, "lastEvent", lastEvent);
        b.put("root", root);
        Json.putIf(b, "previous", previous);
        return b;
    }

    public String computeHash() {
        return CanonicalJson.hash(body());
    }

    /** A digest before it is hashed and signed. */
    public static Digest unsigned(String accountId, String region, Instant from, Instant to, int count, String firstEvent, String lastEvent,
            String root, String previous) {
        return new Digest(idOf(accountId, region, from), accountId, region, from, to, count, firstEvent, lastEvent, root, previous, null, null,
                null, null);
    }

    public Digest signed(DigestSigner signer, Instant at) {
        String h = computeHash();
        return new Digest(id, accountId, region, from, to, count, firstEvent, lastEvent, root, previous, h, signer.keyId(), signer.sign(h), at);
    }
}
