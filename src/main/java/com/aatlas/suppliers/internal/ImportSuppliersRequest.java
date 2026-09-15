package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A list of suppliers to put on the panel in one go.
 *
 * <p>Records rather than a file, because the browser has already parsed the CSV and shown the
 * buyer a dry run of it. Sending the rows that passed means the API is not asked to guess at a
 * dialect that has already been resolved, and the preview on screen is exactly what is sent.
 *
 * <p>The API still re-validates every row and has the last word: what the browser checked was
 * the file's shape, and only the server knows the panel, the categories and what a supplier
 * may claim about itself.
 */
// The elements are deliberately not @Valid: bean validation fails the whole request for one
// bad element, and an import has to reject the bad row and keep the other forty-nine. Each row
// is checked in the service instead, which is what puts it in "rejected" with its index.
@Schema(name = "ImportSuppliersRequest", description = "Suppliers parsed from a file by the client.")
record ImportSuppliersRequest(
        @Schema(description = "The rows that passed the client's own check.")
                @NotEmpty(message = "Send at least one supplier.")
                @Size(max = 2000, message = "That is more suppliers than one import should carry.")
                List<AddSupplierRequest> suppliers) {
}
