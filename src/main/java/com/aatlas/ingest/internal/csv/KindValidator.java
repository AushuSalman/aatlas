package com.aatlas.ingest.internal.csv;

import java.util.List;
import java.util.function.Consumer;

/**
 * Reads a file of one kind and says what is wrong with it.
 *
 * <p>Stateless: one bean per kind serves every concurrent upload. The acceptance rule is the
 * same in {@link #validate} and {@link #forEachAcceptedRow}, so a row that validated is a row
 * that loads.
 */
public interface KindValidator<R extends AcceptedRow> {

    ImportKind kind();

    ImportReport<R> validate(List<List<String>> rows, ColumnMapping mapping, ValidationContext ctx);

    ImportReport<R> validate(String csv, ColumnMapping mapping, ValidationContext ctx);

    /**
     * Hands every accepted row to {@code consumer}, in file order.
     *
     * <p>A callback rather than a returned list because this is what the loader uses, and a
     * 24-month export is hundreds of thousands of rows that have no reason to exist in
     * memory at once.
     */
    void forEachAcceptedRow(String csv, ColumnMapping mapping, ValidationContext ctx, Consumer<R> consumer);
}
