package com.iaf.console.fake.quotas.domain;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A token bucket: so many tokens a minute up to a burst, refilled continuously, read as a Quota document. */
public final class TokenBucket {

    private final String key;
    private int limitPerMinute;
    private int burst;
    private double available;
    private Instant lastRefill;
    private String source;

    public TokenBucket(String key, int limitPerMinute, int burst, Instant now, String source) {
        this.key = key;
        this.limitPerMinute = limitPerMinute;
        this.burst = burst;
        this.available = burst;
        this.lastRefill = now;
        this.source = source;
    }

    public String key() {
        return key;
    }

    public synchronized int limitPerMinute() {
        return limitPerMinute;
    }

    public synchronized int burst() {
        return burst;
    }

    /** Takes one token; answers 0 when it could, else the seconds until the next token. */
    public synchronized int take(Instant now) {
        refill(now);
        if (available >= 1) {
            available -= 1;
            return 0;
        }
        if (limitPerMinute <= 0) {
            return 60;
        }
        double missing = 1 - available;
        return (int) Math.max(1, Math.ceil(missing * 60.0 / limitPerMinute));
    }

    /** Sets new limits (an override); the bucket keeps what it holds, capped at the new burst. */
    public synchronized void override(int newLimitPerMinute, int newBurst, Instant now) {
        refill(now);
        this.limitPerMinute = newLimitPerMinute;
        this.burst = newBurst;
        this.available = Math.min(available, newBurst);
        this.source = "override";
    }

    public synchronized ObjectNode document(Instant now) {
        refill(now);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("key", key);
        out.put("limitPerMinute", limitPerMinute);
        out.put("burst", burst);
        out.put("available", (int) Math.floor(available));
        if (available < burst && limitPerMinute > 0) {
            double missing = Math.floor(available) + 1 - available;
            long millis = (long) Math.ceil(missing * 60_000.0 / limitPerMinute);
            out.put("refillsAt", now.plus(Duration.ofMillis(millis)).toString());
        }
        out.put("source", source);
        return out;
    }

    private void refill(Instant now) {
        if (now.isAfter(lastRefill) && limitPerMinute > 0) {
            double minutes = Duration.between(lastRefill, now).toMillis() / 60_000.0;
            available = Math.min(burst, available + minutes * limitPerMinute);
        }
        lastRefill = now;
    }
}
