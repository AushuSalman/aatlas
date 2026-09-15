package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What a supplier import did.
 *
 * <p>The split between added and updated matters, and is the reason this is not simply a
 * count: a file is nearly always a mix of suppliers the panel has never seen and corrections
 * to ones it has, and only the server can say which is which, because only it knows the whole
 * panel. The screen tells the buyer "4 added, 2 updated" off the back of this.
 *
 * @param rejected rows the server would not take, indexed into what was sent, so the client
 *     can point at the row rather than saying the import half-worked
 */
@Schema(name = "ImportSuppliersResponse")
record ImportSuppliersResponse(
        List<SupplierProfileView> added,
        List<SupplierProfileView> updated,
        List<Rejection> rejected) {

    /**
     * One supplier the server would not take, and why.
     *
     * @param index position in the submitted list, so the client can highlight that row
     * @param name the supplier as submitted, for a message that reads without the index
     * @param field the field at fault, when one field is at fault
     */
    @Schema(name = "SupplierImportRejection")
    record Rejection(int index, String name, String message, String field) {
    }
}
