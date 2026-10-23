package com.iaf.console.state.trail.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.iaf.console.state.inventory.domain.EventChain;
import com.iaf.console.state.inventory.domain.ResourceEvent;

/**
 * The running root of a period's events: each event's hash, worked out again from its stored form,
 * and a newline, fed in the log's time order. Recomputing it means a changed event no longer adds
 * up to its digest even when its stored hash was left alone.
 */
public final class DigestRoot {

    private final MessageDigest sha256;
    private int count;
    private String first;
    private String last;

    public DigestRoot() {
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JVM has no SHA-256", e);
        }
    }

    public DigestRoot add(ResourceEvent e) {
        String hash = EventChain.hash(e);
        sha256.update((hash + "\n").getBytes(StandardCharsets.UTF_8));
        if (first == null) {
            first = e.id();
        }
        last = e.id();
        count++;
        return this;
    }

    public int count() {
        return count;
    }

    public String first() {
        return first;
    }

    public String last() {
        return last;
    }

    /** The root so far; the digest object is spent afterwards. */
    public String root() {
        return "sha256:" + HexFormat.of().formatHex(sha256.digest());
    }
}
