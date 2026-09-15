package com.aatlas.common.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * A CSV reader for the things real exports actually contain.
 *
 * <p>Quoted fields, commas inside quotes, doubled quotes as an escape, CRLF endings, and
 * the byte-order mark Excel adds without asking. A port of {@code parseCsv} in the
 * frontend's {@code ingest.ts}, character for character, because the connect screen shows
 * the user a preview parsed by that function and the server must see the same rows.
 *
 * <p>Deliberately not a complete RFC 4180 implementation, and commons-csv is deliberately
 * not used here despite being on the classpath: both would disagree with the browser in
 * edge cases, and agreeing with the browser is the entire requirement. Anything this
 * cannot read becomes a row issue rather than a guess.
 */
public final class CsvReader {

    private CsvReader() {
    }

    /**
     * Splits {@code text} into rows of fields.
     *
     * <p>Rows that are entirely empty are dropped, which is what removes the trailing blank
     * line every file ending in a newline produces.
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        String body = stripByteOrderMark(text);

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is one literal quote.
                    if (i + 1 < body.length() && body.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                }
                case '\n' -> {
                    row.add(field.toString());
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                }
                // A bare CR is a line ending's other half and never data.
                case '\r' -> { }
                default -> field.append(c);
            }
        }

        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }

        return rows.stream().filter(CsvReader::hasContent).toList();
    }

    /** True unless the row is a single blank field - the shape a trailing newline leaves. */
    private static boolean hasContent(List<String> row) {
        return row.size() > 1 || !row.getFirst().isBlank();
    }

    private static String stripByteOrderMark(String text) {
        return text != null && text.startsWith("﻿") ? text.substring(1) : (text == null ? "" : text);
    }
}
