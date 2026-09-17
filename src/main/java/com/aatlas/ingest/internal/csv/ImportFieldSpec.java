package com.aatlas.ingest.internal.csv;

import java.util.List;
import java.util.Locale;

/**
 * One column an import kind can carry: what it is called on the wire, how it is detected and
 * how the data guide describes it.
 *
 * <p>Implemented by one enum per {@link ImportKind}. Declaration order in each enum is the
 * detection order, which puts the required fields first so they are matched before an
 * optional one can take a column both would accept.
 */
public interface ImportFieldSpec {

    /** Wire key, e.g. {@code qty}; the mapping JSON and the issues table use it. */
    String key();

    String label();

    boolean required();

    /** The one-line consequence of leaving this column out, shown beside the picker. */
    String hint();

    /** The header the template uses - always an exact synonym, so templates map in pass 1. */
    String header();

    /** A realistic value, for the field table on the data guide. */
    String example();

    /** Post-{@link #normalise} header names real ERP exports emit. */
    List<String> synonyms();

    /**
     * Header comparison form: lower-cased with everything but letters and digits removed,
     * so {@code "Qty Shipped"}, {@code "QTY_SHIPPED"} and {@code "qty-shipped"} are one name.
     */
    static String normalise(String header) {
        if (header == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(header.length());
        header.toLowerCase(Locale.ROOT).chars()
                .filter(Character::isLetterOrDigit)
                .forEach(c -> out.append((char) c));
        return out.toString();
    }
}
