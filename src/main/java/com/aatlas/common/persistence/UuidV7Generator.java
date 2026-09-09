package com.aatlas.common.persistence;

import java.lang.reflect.Member;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;
import org.hibernate.id.factory.spi.CustomIdGeneratorCreationContext;

/**
 * RFC 9562 UUID version 7: 48 bits of Unix milliseconds, then randomness.
 *
 * <p>The layout is fixed by the spec and mirrors {@code app.uuid_generate_v7()} in
 * {@code V1__foundations.sql}, so a row inserted by JPA and one inserted by a migration
 * or a bulk COPY sort the same way:
 *
 * <pre>
 *   bytes 0-5   unix_ts_ms, big-endian
 *   byte  6     version 7 in the high nibble, random low nibble
 *   byte  8     variant 0b10 in the top two bits, random remainder
 *   bytes 9-15  random
 * </pre>
 *
 * <p>A per-millisecond counter is deliberately not implemented. Two ids generated in the
 * same millisecond are ordered arbitrarily relative to each other but still sort into the
 * correct millisecond bucket, which is all the clustering benefit requires; ordering
 * within a millisecond is not something callers may rely on.
 */
public class UuidV7Generator implements BeforeExecutionGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Hibernate instantiates the generator through this constructor when it meets
     * {@link UuidV7}. None of the arguments are needed - the layout has no options - but
     * the signature is what makes the annotation binding work.
     */
    public UuidV7Generator(UuidV7 config, Member member, CustomIdGeneratorCreationContext context) {
        // Intentionally empty: see the class javadoc.
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }

    @Override
    public Object generate(
            SharedSessionContractImplementor session,
            Object owner,
            Object currentValue,
            EventType eventType) {
        // An id assigned explicitly wins: imports and tests need to control it.
        return currentValue instanceof UUID assigned ? assigned : next();
    }

    /** A fresh v7 identifier. Public because tests and fixtures need one without a session. */
    public static UUID next() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);

        long timestamp = System.currentTimeMillis();
        bytes[0] = (byte) (timestamp >>> 40);
        bytes[1] = (byte) (timestamp >>> 32);
        bytes[2] = (byte) (timestamp >>> 24);
        bytes[3] = (byte) (timestamp >>> 16);
        bytes[4] = (byte) (timestamp >>> 8);
        bytes[5] = (byte) timestamp;

        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (bytes[i] & 0xFFL);
        }
        for (int i = 8; i < 16; i++) {
            low = (low << 8) | (bytes[i] & 0xFFL);
        }
        return new UUID(high, low);
    }
}
