package com.iaf.platform.resources.domain;

import java.security.SecureRandom;

import com.iaf.platform.common.domain.Ids;

/** Registry ids: {@code <type>-<6 chars>}, like {@code vm-8f2k1x}. */
public final class ResourceIds {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int SUFFIX_LENGTH = 6;

    private ResourceIds() {
    }

    public static String mint(String type) {
        StringBuilder sb = new StringBuilder(Ids.slug(type)).append('-');
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** The type an id was minted for, when it has the registry shape; null otherwise. */
    public static String typeOf(String id) {
        if (id == null) {
            return null;
        }
        int dash = id.lastIndexOf('-');
        return dash <= 0 ? null : id.substring(0, dash);
    }
}
