package com.iaf.console.wfrn;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * ULIDs in the lowercase Crockford base32 form the wfrn specification uses: 48 bits of time, then
 * 80 bits of randomness, monotonic within one millisecond so the ids minted in a burst sort in
 * the order they were made.
 */
public final class Ulid {

    /** The length of a ULID in characters. */
    public static final int LENGTH = 26;

    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final byte[] DECODE = new byte[128];
    private static final long MAX_TIME = (1L << 48) - 1;
    private static final long MASK_16 = 0xFFFFL;
    private static final Ulid DEFAULT = new Ulid(Clock.systemUTC(), new SecureRandom());

    static {
        java.util.Arrays.fill(DECODE, (byte) -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE[ALPHABET[i]] = (byte) i;
        }
    }

    private final Clock clock;
    private final RandomGenerator random;
    private long lastTime = -1;
    private long lastHi;
    private long lastLo;

    /** A generator on its own clock and randomness: the default one uses the system clock and {@link SecureRandom}. */
    public Ulid(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    /** The next ULID of the shared generator. */
    public static String next() {
        return DEFAULT.generate();
    }

    /** A ULID of a given time and randomness, for deterministic seeds and tests. */
    public static String of(long epochMillis, RandomGenerator random) {
        return encode(epochMillis, random.nextLong() & MASK_16, random.nextLong());
    }

    /** A ULID of a given time and randomness. */
    public static String of(Instant at, RandomGenerator random) {
        return of(at.toEpochMilli(), random);
    }

    /** The next ULID of this generator: a fresh random part in a new millisecond, the last one plus one within the same. */
    public synchronized String generate() {
        long now = clock.millis();
        if (now <= lastTime) {
            lastLo++;
            if (lastLo == 0) {
                lastHi = (lastHi + 1) & MASK_16;
                if (lastHi == 0) {
                    // the 80 random bits wrapped within one millisecond: move to the next one
                    lastTime++;
                    lastHi = random.nextLong() & MASK_16;
                    lastLo = random.nextLong();
                }
            }
        } else {
            lastTime = now;
            lastHi = random.nextLong() & MASK_16;
            lastLo = random.nextLong();
        }
        return encode(lastTime, lastHi, lastLo);
    }

    /** Whether a string is a lowercase ULID: 26 characters of the alphabet, the first at most {@code 7}. */
    public static boolean isValid(String s) {
        if (s == null || s.length() != LENGTH) {
            return false;
        }
        char first = s.charAt(0);
        if (first < '0' || first > '7') {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= DECODE.length || DECODE[c] < 0) {
                return false;
            }
        }
        return true;
    }

    /** The time a ULID encodes. */
    public static Instant timestamp(String ulid) {
        if (!isValid(ulid)) {
            throw new IllegalArgumentException("'" + ulid + "' is not a lowercase ULID");
        }
        long time = 0;
        for (int i = 0; i < 10; i++) {
            time = (time << 5) | DECODE[ulid.charAt(i)];
        }
        return Instant.ofEpochMilli(time);
    }

    static String encode(long time, long hi16, long lo64) {
        if (time < 0 || time > MAX_TIME) {
            throw new IllegalArgumentException("a ULID time fits in 48 bits, " + time + " does not");
        }
        char[] out = new char[LENGTH];
        long t = time;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (t & 0x1F)];
            t >>>= 5;
        }
        // 80 random bits, hi16 above lo64, written as 16 characters of 5 bits from the end
        long bits = lo64;
        for (int i = 25; i >= 14; i--) {
            out[i] = ALPHABET[(int) (bits & 0x1F)];
            bits >>>= 5;
        }
        // the 4 bits of lo64 left over, under the 16 bits of hi16: 20 bits, 4 characters
        long top = ((hi16 & MASK_16) << 4) | (lo64 >>> 60);
        for (int i = 13; i >= 10; i--) {
            out[i] = ALPHABET[(int) (top & 0x1F)];
            top >>>= 5;
        }
        return new String(out);
    }
}
