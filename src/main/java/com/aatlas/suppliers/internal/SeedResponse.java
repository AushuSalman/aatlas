package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** How many suppliers the seed call added. Zero when the tenant already had its panel. */
@Schema(name = "SupplierSeedResponse")
record SeedResponse(int seeded) {
}
