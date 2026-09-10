package com.aatlas.common.seed;

import java.util.List;

/**
 * The deterministic "random" the prototype's fixtures are built from, ported exactly.
 *
 * <p>Every seeded number in the frontend comes from {@code rand(key, salt)} in
 * {@code src/lib/platform/data.ts}: an FNV-1a 32-bit hash over the UTF-16 code units of
 * {@code key + "::" + salt}, reinterpreted as a signed int, made absolute, reduced modulo
 * 100000 and divided by 100000. The engines ported to Java must reproduce those numbers to
 * the last digit, so this class mirrors the JavaScript arithmetic step for step and is
 * pinned by {@code golden/rand.json} in the tests.
 *
 * <p>Two traps the port avoids: Java's {@code Math.abs(Integer.MIN_VALUE)} stays negative
 * where JavaScript's does not (so the absolute value is taken as a long), and the hash runs
 * over UTF-16 code units, not code points, exactly like {@code charCodeAt}.
 */
public final class Seeded {

    private static final int OFFSET_BASIS = 0x811c9dc5;
    private static final int PRIME = 0x01000193;

    private Seeded() {}

    /** FNV-1a over UTF-16 code units; {@code Math.abs(h | 0)} in the original. */
    public static long hashString(String s) {
        int h = OFFSET_BASIS;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h = h * PRIME; // 32-bit wrapping multiply, the same as Math.imul
        }
        return Math.abs((long) h);
    }

    /** A stable value in [0, 1) for a key and salt. */
    public static double rand(String key, String salt) {
        return (hashString(key + "::" + salt) % 100000L) / 100000.0;
    }

    public static double rand(String key) {
        return rand(key, "");
    }

    /** A stable value in [min, max] for a key and salt. */
    public static double randRange(String key, String salt, double min, double max) {
        return min + rand(key, salt) * (max - min);
    }

    /** A stable integer in [min, max] inclusive; the {@code + 0.999} is the original's. */
    public static int randInt(String key, String salt, int min, int max) {
        return (int) Math.floor(randRange(key, salt, min, max + 0.999));
    }

    /** A stable choice from a list. */
    public static <T> T pick(String key, String salt, List<T> list) {
        int n = list.size();
        return list.get(((int) Math.floor(rand(key, salt) * n)) % n);
    }
}
