package com.aatlas.sell.internal.support;

import com.aatlas.common.seed.Seeded;
import com.aatlas.sell.internal.buy.BuySupplierGateway;
import com.aatlas.sell.internal.buy.SupplierRef;
import java.util.List;

/**
 * The 8 seeded suppliers ({@code SUPPLIER_SEED} in {@code src/lib/platform/data.ts}), in
 * their seed order (sup-1..sup-8), with {@code otifPct}/{@code priceIndex} computed by the
 * same {@code randRange(\`sup-${i}\`, 'otif'|'idx', ...)} the frontend uses - not hardcoded,
 * so a change to {@link Seeded} cannot silently drift this fixture out of sync with it.
 */
public final class FixtureSuppliers implements BuySupplierGateway {

    private record Seed(String name, String country) {
    }

    private static final List<Seed> SEED = List.of(
            new Seed("Nordflow Valve Works", "Germany"),
            new Seed("Cascade Copper Mills", "USA"),
            new Seed("Anhui Precision Fittings", "China"),
            new Seed("Gulf States Polymer", "USA"),
            new Seed("Thermaline Systems", "Mexico"),
            new Seed("Kotara Steel Products", "India"),
            new Seed("Larsen Brass & Bronze", "USA"),
            new Seed("Pacific Rim Tooling", "Vietnam"));

    @Override
    public List<SupplierRef> seededPanel() {
        return java.util.stream.IntStream.range(0, SEED.size())
                .mapToObj(i -> {
                    String k = "sup-" + i;
                    double otif = Math.round(Seeded.randRange(k, "otif", 74, 98) * 100) / 100.0;
                    double idx = Math.round(Seeded.randRange(k, "idx", 89, 114) * 100) / 100.0;
                    return new SupplierRef("sup-" + (i + 1), SEED.get(i).name(), otif, idx);
                })
                .toList();
    }
}
