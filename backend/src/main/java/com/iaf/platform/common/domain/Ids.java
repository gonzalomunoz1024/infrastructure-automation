package com.iaf.platform.common.domain;

import java.security.SecureRandom;

/** Short, readable ids with a type prefix: {@code job_8f2k1x9a}. */
public final class Ids {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String next(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('_');
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** kebab-case slug of a human name: "Check VM exists" -> "check-vm-exists". */
    public static String slug(String name) {
        return name == null ? "" : name.toLowerCase().trim().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /** camelCase id of a human name: "Check VM exists" -> "checkVmExists". */
    public static String camel(String name) {
        String[] parts = slug(name).split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            sb.append(sb.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        return sb.toString();
    }
}
