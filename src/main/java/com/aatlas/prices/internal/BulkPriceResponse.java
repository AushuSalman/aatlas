package com.aatlas.prices.internal;

import com.aatlas.history.PriceBook;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What a bulk write did: the id to undo it with, how many rows landed, and which did not. */
record BulkPriceResponse(UUID writeId, int written, List<PriceBook.Skipped> skipped, LocalDate effectiveFrom) {
}
