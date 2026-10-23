package com.iaf.console.bff.clients.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/** The user code: its twenty letters, its eight-letter form, how it is shown and how a typed one is read. */
class UserCodeTest {

    @Test
    void aCodeIsEightLettersOfTheTwentyConsonants() {
        assertEquals(20, UserCode.ALPHABET.length());
        assertEquals(20, UserCode.ALPHABET.chars().distinct().count());
        for (char vowel : "AEIOUY".toCharArray()) {
            assertTrue(UserCode.ALPHABET.indexOf(vowel) < 0, "no vowel: " + vowel);
        }
        SecureRandom random = new SecureRandom();
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = UserCode.generate(random);
            assertTrue(code.matches("^[BCDFGHJKLMNPQRSTVWXZ]{8}$"), code);
            code.chars().forEach(c -> seen.add((char) c));
        }
        assertEquals(20, seen.size(), "every letter is drawn");
        assertNotEquals(UserCode.generate(random), UserCode.generate(random));
    }

    @Test
    void aCodeIsShownWithAHyphenInTheMiddle() {
        String code = UserCode.generate(new SplittableRandom(7));
        String shown = UserCode.display(code);
        assertTrue(shown.matches("^[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}$"), shown);
        assertEquals(code, shown.replace("-", ""));
        assertEquals("BCDF-GHJK", UserCode.display("BCDFGHJK"));
    }

    @Test
    void aTypedCodeIsReadWithOrWithoutItsHyphenInAnyCase() {
        assertEquals(Optional.of("BCDFGHJK"), UserCode.canonical("BCDF-GHJK"));
        assertEquals(Optional.of("BCDFGHJK"), UserCode.canonical("bcdfghjk"));
        assertEquals(Optional.of("BCDFGHJK"), UserCode.canonical(" bcdf-GHJK "));
    }

    @Test
    void anythingElseIsNoCode() {
        for (String typed : new String[] {null, "", "BCDF", "BCDFGHJ", "BCDFGHJKL", "BCDF--GHJK", "BCD-FGHJK", "BCDF GHJK", "ABCDGHJK", "BCDF-GHJ1",
                "BCDFGHJY", "BCDF_GHJK", "bcdfghjß", "bcdfghjı", "ＢＣＤＦＧＨＪＫ"}) {
            assertEquals(Optional.empty(), UserCode.canonical(typed), String.valueOf(typed));
        }
    }
}
