package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.ImportKind;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The downloadable templates: the header each kind detects in pass 1, plus three realistic
 * rows. CRLF line endings, because Excel.
 */
final class ImportTemplates {

    private static final String CRLF = "\r\n";

    private static final List<String> SALES = List.of(
            "Item No,Item Description,Invoice Date,Qty Shipped,Net Price,Unit Cost,Bill To,Whse,Invoice No,Currency,UOM",
            "HRD118902,1/2 IN COPPER TYPE L HARD TUBE 10FT,2026-08-04,48,27.10,19.85,Halloran Mechanical,100959,INV-104211,USD,10 ft length",
            "HRD772310,3/4 IN BRASS BALL VALVE FULL PORT THREADED,2026-08-04,24,16.45,11.90,Ridgeline Plumbing Co.,100117,INV-104212,USD,each",
            "HRD290145,2 IN PVC DWV SANITARY TEE HUB,2026-08-05,200,6.20,4.75,Walk-in / no account,100349,INV-104215,USD,each");

    private static final List<String> PURCHASES = List.of(
            "PO Number,Order Date,Supplier,Supplier Country,Item No,Item Description,Qty Ordered,Unit Cost,Freight,Duty,Landed Cost,Currency,Ship To,Promised Date,Received Date,Qty Received",
            "PO-202607-0412,2026-07-08,Cascade Copper Mills,USA,HRD118902,1/2 IN COPPER TYPE L HARD TUBE 10FT,1200,19.40,0.00,0.00,19.40,USD,100959,2026-07-25,2026-07-27,1200",
            "PO-202607-0418,2026-07-11,Anhui Precision Fittings,China,HRD772310,3/4 IN BRASS BALL VALVE FULL PORT THREADED,600,9.85,0.77,1.23,11.85,USD,100117,2026-09-02,2026-09-06,600",
            "PO-202608-0431,2026-08-19,Gulf States Polymer,USA,HRD290145,2 IN PVC DWV SANITARY TEE HUB,2500,4.62,0.00,0.00,4.62,USD,100349,2026-10-01,,");

    private static final List<String> PRODUCTS = List.of(
            "Item No,Description,Category,Subcategory,Commodity,List Price,Unit Cost,On Hand,Branch,Supplier,Supplier Cost,Lead Time,UOM",
            "HRD118902,1/2 IN COPPER TYPE L HARD TUBE 10FT,Plumbing,Pipe & tube,copper,27.10,19.85,,,Cascade Copper Mills,19.40,17,10 ft length",
            "HRD118902,1/2 IN COPPER TYPE L HARD TUBE 10FT,,,,,,1840,100959,,,,",
            "HRD900002,SMART THERMOSTAT WIFI 24V C-WIRE,HVAC,Controls,equipment,129.00,84.00,,,Pacific Rim Tooling,76.50,38,each");

    private static final List<String> COMPETITOR_PRICES = List.of(
            "Item No,Competitor,Price,Currency,Region/Branch,Observed Date,Source URL",
            "HRD118902,Northline Supply,28.40,USD,south,2026-09-10,",
            "HRD118902,Summit Pipe & Supply,26.75,USD,100959,2026-09-12,",
            "HRD772310,Brightwell Distribution,17.20,USD,TX,2026-09-12,");

    private ImportTemplates() {
    }

    static String template(ImportKind kind) {
        List<String> lines = switch (kind) {
            case SALES -> SALES;
            case PURCHASES -> PURCHASES;
            case PRODUCTS -> PRODUCTS;
            case COMPETITOR_PRICES -> COMPETITOR_PRICES;
        };
        return String.join(CRLF, lines) + CRLF;
    }

    /** The suppliers template: the sample's header and its first three rows. */
    static String suppliersTemplate(byte[] sampleCsv) {
        List<List<String>> rows = com.aatlas.common.csv.CsvReader.parse(new String(sampleCsv, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(4, rows.size()); i++) {
            out.append(rows.get(i).stream().map(ImportTemplates::quote).reduce((a, b) -> a + "," + b).orElse(""))
                    .append(CRLF);
        }
        return out.toString();
    }

    private static String quote(String field) {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
