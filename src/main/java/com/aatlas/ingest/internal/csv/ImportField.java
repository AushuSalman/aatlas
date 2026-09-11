package com.aatlas.ingest.internal.csv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The eight columns an import can carry.
 *
 * <p>A direct port of {@code FIELDS} in the frontend's {@code src/lib/platform/ingest.ts},
 * synonyms included. That file is the specification rather than a reference: the connect
 * screen already applies these rules in the browser, and a file it accepted must not be
 * refused by the server for a reason the user was never shown.
 *
 * <p>Declaration order is load-bearing. {@link ColumnMapping#detect} claims columns in this
 * order and never reassigns one, so the required fields are matched before the optional
 * ones can take a column they would both accept.
 *
 * <p>The synonyms are header names real ERP exports emit - {@code whse}, {@code billto},
 * {@code qtyshipped}. They are not guesses, and inventing tidier ones would only mean
 * detection failing on the files this product exists to read.
 */
public enum ImportField {

    /** Your internal SKU. The spine of everything: without it a row cannot be priced. */
    ITEM("item", "Item number", true,
            "Your internal SKU. Manufacturer and UPC numbers can be mapped later.",
            List.of("item", "itemno", "itemnumber", "sku", "partno", "partnumber",
                    "product", "productcode", "material")),

    /** Invoice or shipment date. 12-24 months of history is the useful range. */
    DATE("date", "Transaction date", true,
            "Invoice or shipment date. 12-24 months of history is the useful range.",
            List.of("date", "invoicedate", "orderdate", "transdate", "transactiondate",
                    "shipdate", "postingdate")),

    QTY("qty", "Quantity", true,
            "Units sold, in the selling unit of measure.",
            List.of("qty", "quantity", "units", "shipqty", "qtyshipped", "quantityshipped")),

    /** Net price actually charged. List price would make every observed margin a fiction. */
    PRICE("price", "Unit price", true,
            "Net price actually charged, after discounts. Not list price.",
            List.of("price", "unitprice", "sellprice", "netprice", "sellingprice", "extprice")),

    COST("cost", "Unit cost", false,
            "Landed cost at the time of sale. Without it, margin cannot be measured.",
            List.of("cost", "unitcost", "landedcost", "stdcost", "standardcost", "cogs")),

    CUSTOMER("customer", "Customer", false,
            "Account the line was sold to. Enables customer-level pricing.",
            List.of("customer", "customerno", "customername", "account", "accountno",
                    "billto", "cust")),

    BRANCH("branch", "Branch", false,
            "Selling location. Without it every branch is priced as one market.",
            List.of("branch", "store", "storeno", "location", "whse", "warehouse",
                    "site", "facility")),

    DESCRIPTION("description", "Description", false,
            "Item description, used for display and for search.",
            List.of("description", "itemdescription", "desc", "productname", "name"));

    private static final Map<String, ImportField> BY_KEY = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ImportField::key, Function.identity()));

    private final String key;
    private final String label;
    private final boolean required;
    private final String hint;
    private final List<String> synonyms;

    ImportField(String key, String label, boolean required, String hint, List<String> synonyms) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.hint = hint;
        this.synonyms = synonyms;
    }

    @JsonValue
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** Required fields with no column assigned block the import; see {@code missingRequired}. */
    public boolean required() {
        return required;
    }

    /** The one-line consequence of leaving this column out, shown beside the picker. */
    public String hint() {
        return hint;
    }

    public List<String> synonyms() {
        return synonyms;
    }

    @JsonCreator
    public static ImportField from(String key) {
        return Optional.ofNullable(key)
                .map(k -> BY_KEY.get(k.strip().toLowerCase(Locale.ROOT)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown import field '" + key + "'. Expected one of " + BY_KEY.keySet()));
    }

    public static List<ImportField> required(java.util.Collection<ImportField> present) {
        return java.util.Arrays.stream(values())
                .filter(ImportField::required)
                .filter(field -> !present.contains(field))
                .toList();
    }

    /**
     * Header comparison form: lower-cased with everything but letters and digits removed,
     * so {@code "Qty Shipped"}, {@code "QTY_SHIPPED"} and {@code "qty-shipped"} are one name.
     */
    public static String normalise(String header) {
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
