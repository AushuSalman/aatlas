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
 * The columns of a purchase-order line export. One row per line; many lines share a PO number.
 *
 * <p>Declaration order is detection order (required fields first). Synonyms come from SAP,
 * Dynamics, NetSuite, Epicor P21/Eclipse, QuickBooks, Sage and Infor exports.
 */
public enum PurchaseField implements ImportFieldSpec {

    ITEM("item", "Item number", true,
            "Your internal SKU. An unknown item is added to the catalogue.",
            "Item No", "HRD118902", ImportField.ITEM.synonyms()),

    ORDER_DATE("orderDate", "Order date", true,
            "When the order was placed. Drives the cost trend and the baseline window.",
            "Order Date", "2026-07-08",
            List.of("orderdate", "podate", "purchasedate", "docdate", "documentdate", "bedat", "orderdt",
                    "createdat")),

    SUPPLIER("supplier", "Supplier", true,
            "Name or vendor code. An unknown supplier is added with no figures until you complete them.",
            "Supplier", "Cascade Copper Mills",
            List.of("supplier", "suppliername", "vendorname", "supplierno", "vendorno", "supplierid",
                    "vendorid", "suppliercode", "vendorcode", "buyfromvendorno", "lifnr", "vendno",
                    "preferredvendor")),

    QTY("qty", "Quantity ordered", true,
            "Units ordered. Cancellations and returns should be excluded.",
            "Qty Ordered", "1200",
            List.of("qtyordered", "orderedqty", "orderqty", "quantityordered", "qty", "quantity", "units",
                    "menge", "qtyord", "poqty")),

    UNIT_COST("unitCost", "Unit cost (ex-works)", true,
            "What the supplier charged per unit before freight and duty.",
            "Unit Cost", "19.40",
            List.of("unitcost", "exworks", "exw", "exworkscost", "unitcostexworks", "cost", "unitprice",
                    "netprice", "purchaseprice", "poprice", "netpr", "rate", "costeach")),

    PO_NUMBER("poNumber", "PO number", false,
            "The order reference as your system wrote it. Many lines can share one.",
            "PO Number", "PO-202607-0412",
            List.of("ponumber", "pono", "po", "ponum", "poref", "purchaseorder", "purchaseorderno",
                    "purchaseordernumber", "ordernumber", "orderno", "documentno", "docno", "ebeln")),

    SUPPLIER_COUNTRY("supplierCountry", "Supplier country", false,
            "Where the goods ship from. Picks the freight and duty lane for the estimate.",
            "Supplier Country", "USA",
            List.of("suppliercountry", "vendorcountry", "country", "origin", "countryoforigin",
                    "origincountry", "shipfrom", "sourcecountry")),

    DESCRIPTION("description", "Item description", false,
            "Item description, used when the item is new to the catalogue.",
            "Item Description", "1/2 IN COPPER TYPE L HARD TUBE 10FT", ImportField.DESCRIPTION.synonyms()),

    FREIGHT("freight", "Freight per unit", false,
            "Inbound freight per unit. Blank means none, or the gap between landed and ex-works.",
            "Freight", "0.00",
            List.of("freight", "freightcost", "freightperunit", "shipping", "shippingcost", "inboundfreight",
                    "carriage", "transport")),

    DUTY("duty", "Duty per unit", false,
            "Import duty per unit. Blank means none.",
            "Duty", "0.00",
            List.of("duty", "duties", "tariff", "customs", "customsduty", "importduty", "dutycost")),

    LANDED_COST("landedCost", "Landed cost", false,
            "Full delivered cost per unit. When only this is given, the gap over ex-works is read as freight.",
            "Landed Cost", "19.40",
            List.of("landedcost", "landed", "landedunitcost", "deliveredcost", "totalunitcost", "fullcost")),

    CURRENCY("currency", "Currency", false,
            "Must be your trading currency. Blank means it already is.",
            "Currency", "USD", ImportField.CURRENCY.synonyms()),

    SHIP_TO("shipTo", "Ship-to branch", false,
            "The branch the order was delivered to. Blank goes to your main branch.",
            "Ship To", "100959",
            List.of("shipto", "shiptobranch", "shiptolocation", "branch", "warehouse", "whse", "locationcode",
                    "site", "siteid", "plant", "werks", "destination", "deliverto", "storeno")),

    PROMISED_DATE("promisedDate", "Promised date", false,
            "When the supplier promised delivery. Needed to measure on-time performance.",
            "Promised Date", "2026-07-25",
            List.of("promiseddate", "promisedate", "promisedreceiptdate", "expecteddate", "expectedreceiptdate",
                    "duedate", "requireddate", "requestdate", "eta", "duedt", "eindt")),

    RECEIVED_DATE("receivedDate", "Received date", false,
            "When the goods arrived. Blank means not yet received.",
            "Received Date", "2026-07-27",
            List.of("receiveddate", "datereceived", "receiptdate", "receivedon", "deliverydate", "delivereddate",
                    "grdate", "goodsreceiptdate", "recdt")),

    QTY_RECEIVED("qtyReceived", "Quantity received", false,
            "Units actually received, if short shipments happen.",
            "Qty Received", "1200",
            List.of("qtyreceived", "receivedqty", "quantityreceived", "receivedquantity", "qtyrcv", "recqty"));

    private static final Map<String, PurchaseField> BY_KEY = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(PurchaseField::key, Function.identity()));

    private final String key;
    private final String label;
    private final boolean required;
    private final String hint;
    private final String header;
    private final String example;
    private final List<String> synonyms;

    PurchaseField(String key, String label, boolean required, String hint, String header, String example,
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

    @Override
    public boolean required() {
        return required;
    }

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
    public static PurchaseField from(String key) {
        return Optional.ofNullable(key)
                .map(k -> BY_KEY.get(k.strip()))
                .or(() -> Optional.ofNullable(key).map(k -> byLowerKey(k.strip())))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown purchases field '" + key + "'. Expected one of " + BY_KEY.keySet()));
    }

    private static PurchaseField byLowerKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return BY_KEY.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase(Locale.ROOT).equals(lower))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public static List<PurchaseField> required(java.util.Collection<? extends ImportFieldSpec> present) {
        return java.util.Arrays.stream(values())
                .filter(PurchaseField::required)
                .filter(field -> !present.contains(field))
                .toList();
    }

    public static String normalise(String header) {
        return ImportFieldSpec.normalise(header);
    }
}
