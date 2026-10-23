package com.iaf.console.kit.events;

import java.time.Instant;

/**
 * Deterministic event keys, the platform's EventKeys rules: a job event is
 * {@code <type>:<jobId>:<seq>}, a resource event {@code <type>:<resourceId>:<updatedAt epoch nanos>},
 * a content event {@code <type>:<name>:<version>}. The same fact always gets the same key.
 */
public final class EventKeys {

    private EventKeys() {
    }

    /** A key from a type, the aggregate it is about and what makes this occurrence unique. */
    public static String of(String type, String aggregateId, String discriminator) {
        if (type == null || type.isBlank() || aggregateId == null || aggregateId.isBlank() || discriminator == null || discriminator.isBlank()) {
            throw new IllegalArgumentException("an event key needs a type, an aggregate id and a discriminator");
        }
        return type + ":" + aggregateId + ":" + discriminator;
    }

    public static String job(String type, String jobId, long jobEventSeq) {
        return of(type, jobId, Long.toString(jobEventSeq));
    }

    public static String resource(String type, String resourceId, Instant updatedAt) {
        return of(type, resourceId, Long.toString(nanos(updatedAt)));
    }

    public static String content(String type, String name, String version) {
        return of(type, name, version);
    }

    /** An instant as epoch nanoseconds, the discriminator resource events use. */
    public static long nanos(Instant at) {
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }
}
