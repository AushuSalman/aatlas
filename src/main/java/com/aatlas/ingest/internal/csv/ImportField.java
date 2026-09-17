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
 * The columns a sales-history import can carry.
 *
 * <p>A direct port of {@code FIELDS} in the frontend's {@code src/lib/platform/ingest.ts},
 * synonyms included, plus the three optional columns real invoice exports carry (invoice
 * number, currency, unit of measure). That file is the specification rather than a
 * reference: the connect screen already applies these rules in the browser, and a file it
 * accepted must not be refused by the server for a reason the user was never shown.
 *
 * <p>Declaration order is load-bearing. {@link ColumnMapping#detect} claims columns in this
 * order and never reassigns one, so the required fields are matched before the optional
 * ones can take a column they would both accept.
 *
 * <p>The synonyms are header names real ERP exports emit - {@code whse}, {@code billto},
 * {@code qtyshipped}, {@code matnr}. They are not guesses, and inventing tidier ones would
 * only mean detection failing on the files this product exists to read.
 */
public enum ImportField implements ImportFieldSpec {

    /** Your internal SKU. The spine of everything: without it a row cannot be priced. */
    ITEM("item", "Item number", true,
            "Your internal SKU. Manufacturer and UPC numbers can be mapped later.",
            "Item No", "HRD118902",
            List.of("item", "itemno", "itemnumber", "sku", "partno", "partnumber",
                    "product", "productcode", "material",
                    "itemid", "itemcode", "stockcode", "matnr", "materialno", "materialnumber", "prod",
                    "productno", "productnumber", "productid", "productsku", "variantsku", "lineitemsku")),

    /** Invoice or shipment date. 12-24 months of history is the useful range. */
    DATE("date", "Transaction date", true,
            "Invoice or shipment date. 12-24 months of history is the useful range.",
            "Invoice Date", "2026-08-04",
            List.of("date", "invoicedate", "orderdate", "transdate", "transactiondate",
                    "shipdate", "postingdate")),

    QTY("qty", "Quantity", true,
            "Units sold, in the selling unit of measure.",
            "Qty Shipped", "48",
            List.of("qty", "quantity", "units", "shipqty", "qtyshipped", "quantityshipped")),

    /** Net price actually charged. List price would make every observed margin a fiction. */
    PRICE("price", "Unit price", true,
            "Net price actually charged, after discounts. Not list price.",
            "Net Price", "27.10",
            List.of("price", "unitprice", "sellprice", "netprice", "sellingprice")),

    COST("cost", "Unit cost", false,
            "Landed cost at the time of sale. Without it, margin cannot be measured.",
            "Unit Cost", "19.85",
            List.of("cost", "unitcost", "landedcost", "stdcost", "standardcost", "cogs")),

    CUSTOMER("customer", "Customer", false,
            "Account the line was sold to. Enables customer-level pricing.",
            "Bill To", "Halloran Mechanical",
            List.of("customer", "customerno", "customername", "account", "accountno",
                    "billto", "cust")),

    BRANCH("branch", "Branch", false,
            "Selling location. Without it every branch is priced as one market.",
            "Whse", "100959",
            List.of("branch", "store", "storeno", "location", "whse", "warehouse",
                    "site", "facility")),

    DESCRIPTION("description", "Description", false,
            "Item description, used for display and for search.",
            "Item Description", "1/2 IN COPPER TYPE L HARD TUBE 10FT",
            List.of("description", "itemdescription", "desc", "productname", "name",
                    "itemdesc", "productdesc", "productdescription", "materialdescription", "maktx",
                    "displayname", "title")),

    INVOICE_NO("invoiceNo", "Invoice number", false,
            "The invoice or document the line belongs to. Lets a re-import be traced.",
            "Invoice No", "INV-104211",
            List.of("invoiceno", "invoicenumber", "invoice", "num", "docno", "documentno",
                    "documentnumber", "vbeln", "ordernumber", "orderno")),

    CURRENCY("currency", "Currency", false,
            "Must be your trading currency. Blank means it already is.",
            "Currency", "USD",
            List.of("currency", "currencycode", "curr", "ccy", "waers", "currencyid")),

    UOM("uom", "Unit of measure", false,
            "The selling unit the quantity is counted in.",
            "UOM", "10 ft length",
            List.of("uom", "unitofmeasure", "sellingunit", "unitofsale", "meins", "um"));

    private static final Map<String, ImportField> BY_KEY = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ImportField::key, Function.identity()));

    private final String key;
    private final String label;
    private final boolean required;
    private final String hint;
    private final String header;
    private final String example;
    private final List<String> synonyms;

    ImportField(String key, String label, boolean required, String hint, String header, String example,
            List<String> synonyms) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.hint = hint;
        this.header = header;
        this.example = example;
        this.synonyms = synonyms;
    }

    @Override
    @JsonValue
    public String key() {
        return key;
    }

    @Override
    public String label() {
        return label;
    }

    /** Required fields with no column assigned block the import; see {@code missingRequired}. */
    @Override
    public boolean required() {
        return required;
    }

    /** The one-line consequence of leaving this column out, shown beside the picker. */
    @Override
    public String hint() {
        return hint;
    }

    @Override
    public String header() {
        return header;
    }

    @Override
    public String example() {
        return example;
    }

    @Override
    public List<String> synonyms() {
        return synonyms;
    }

    @JsonCreator
    public static ImportField from(String key) {
        return Optional.ofNullable(key)
                .map(k -> BY_KEY.get(k.strip()))
                .or(() -> Optional.ofNullable(key).map(k -> BY_KEY.get(k.strip().toLowerCase(Locale.ROOT))))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown import field '" + key + "'. Expected one of " + BY_KEY.keySet()));
    }

    public static List<ImportField> required(java.util.Collection<? extends ImportFieldSpec> present) {
        return java.util.Arrays.stream(values())
                .filter(ImportField::required)
                .filter(field -> !present.contains(field))
                .toList();
    }

    /** See {@link ImportFieldSpec#normalise}. */
    public static String normalise(String header) {
        return ImportFieldSpec.normalise(header);
    }
}
