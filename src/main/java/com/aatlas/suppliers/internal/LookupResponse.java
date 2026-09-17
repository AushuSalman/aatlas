package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * What "pull their information from the web" honestly returns: nothing was fetched.
 *
 * @param found always {@code false} today - the platform does not fetch supplier data
 * @param query the buyer's own text, tidied (whitespace collapsed)
 * @param country the country the buyer supplied, verbatim; null when they did not give one
 * @param draft a starting point for the panel form: the query read as a name, and the
 *     country, if any - never a fact the platform claims to have found
 * @param sources always empty - nowhere was checked
 * @param message what the UI shows in place of a result
 * @param lookupId the id to reference this lookup by when adding the drafted supplier
 */
@Schema(name = "SupplierLookupResult")
record LookupResponse(
        boolean found,
        String query,
        String country,
        LookupScoring.Draft draft,
        List<LookupSource> sources,
        String message,
        UUID lookupId) {

    static final String MESSAGE = "We don't fetch company data yet; enter what you know or import a CSV";
}
