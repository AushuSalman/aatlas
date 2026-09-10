package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** One place the lookup checked, and what it found there. */
@Schema(name = "LookupSource")
record LookupSource(String source, boolean found, String detail) {
}
