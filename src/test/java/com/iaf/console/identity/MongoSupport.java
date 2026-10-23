package com.iaf.console.identity;

import java.util.concurrent.ThreadLocalRandom;

/** Where the MongoDB tests run: the database {@code CONSOLE_TEST_MONGO_URI} names (CI runs mongo:7); without it they are skipped. */
public final class MongoSupport {

    public static final String URI_VARIABLE = "CONSOLE_TEST_MONGO_URI";

    private MongoSupport() {
    }

    /** What {@code @EnabledIf} asks. */
    public static boolean available() {
        String uri = System.getenv(URI_VARIABLE);
        return uri != null && !uri.isBlank();
    }

    public static String uri() {
        return System.getenv(URI_VARIABLE).trim();
    }

    /** A database name no other test run uses. */
    public static String freshDatabase(String prefix) {
        return prefix + "_" + Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
    }
}
