package com.iaf.console.bff.clients.domain;

import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The code a person types to approve a device grant (RFC 8628 §6.1): eight letters from twenty
 * consonants, so it holds no vowel to spell a word with and no letter that reads as a digit, shown
 * as {@code XXXX-XXXX}. It is kept in its canonical form, the eight capitals without the hyphen,
 * and matched without the hyphen and whatever the case.
 */
public final class UserCode {

    /** The letters a code is drawn from. */
    public static final String ALPHABET = "BCDFGHJKLMNPQRSTVWXZ";
    /** The letters of a code. */
    public static final int LENGTH = 8;

    private UserCode() {
    }

    /** A new code in its canonical form, each letter drawn evenly from the alphabet. */
    public static String generate(RandomGenerator random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** The code as the CLI and the page show it: {@code XXXX-XXXX}. */
    public static String display(String canonical) {
        return canonical.substring(0, LENGTH / 2) + "-" + canonical.substring(LENGTH / 2);
    }

    /**
     * The canonical form of what a person typed: four letters, an optional hyphen and four letters,
     * in any case, each of the alphabet; empty for anything else.
     */
    public static Optional<String> canonical(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String code = typed.strip();
        if (code.length() == LENGTH + 1 && code.charAt(LENGTH / 2) == '-') {
            code = code.substring(0, LENGTH / 2) + code.substring(LENGTH / 2 + 1);
        }
        if (code.length() != LENGTH) {
            return Optional.empty();
        }
        StringBuilder canonical = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            char c = code.charAt(i);
            // ASCII letters only: another script's case mapping could change the length
            char upper = c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c;
            if (ALPHABET.indexOf(upper) < 0) {
                return Optional.empty();
            }
            canonical.append(upper);
        }
        return Optional.of(canonical.toString());
    }
}
