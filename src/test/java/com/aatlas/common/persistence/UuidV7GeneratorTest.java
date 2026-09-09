package com.aatlas.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The properties the schema's clustering argument actually depends on.
 *
 * <p>If any of these stop holding, {@code V1}'s claim that inserts stay at the right-hand
 * edge of the index quietly stops being true, and nothing else would notice.
 */
class UuidV7GeneratorTest {

    @Test
    @DisplayName("carries version 7 and the RFC 4122 variant")
    void hasCorrectVersionAndVariant() {
        for (int i = 0; i < 1_000; i++) {
            UUID id = UuidV7Generator.next();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("ids generated later sort after ids generated earlier")
    void isTimeOrdered() throws InterruptedException {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(UuidV7Generator.next());
            // Past the millisecond boundary: ordering within a single millisecond is
            // explicitly not promised, so testing it would be testing a non-guarantee.
            Thread.sleep(2);
        }

        List<UUID> sorted = ids.stream().sorted(UuidV7GeneratorTest::compareUnsigned).toList();
        assertThat(ids).containsExactlyElementsOf(sorted);
    }

    @Test
    @DisplayName("the timestamp prefix is the current wall clock in milliseconds")
    void embedsCurrentTimestamp() {
        long before = System.currentTimeMillis();
        UUID id = UuidV7Generator.next();
        long after = System.currentTimeMillis();

        long embedded = id.getMostSignificantBits() >>> 16;
        assertThat(embedded).isBetween(before, after);
    }

    @Test
    @DisplayName("does not collide across many generations")
    void isUnique() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            assertThat(seen.add(UuidV7Generator.next())).isTrue();
        }
    }

    /**
     * {@link UUID#compareTo} signs the halves, so it disagrees with PostgreSQL's unsigned
     * byte ordering exactly where the high bit is set. The index order is the one that
     * matters here.
     */
    private static int compareUnsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
