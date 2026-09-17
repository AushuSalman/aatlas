package com.aatlas.ingest.internal.csv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The columns of a product master: the item list with category, unit and commodity, plus a
 * list price, a cost, stock on hand and a preferred supplier.
 *
 * <p>{@link #UOM} is declared last on purpose: its synonyms ({@code um}, {@code unitofmeasure})
 * are short and generic, and detected earlier they would take {@code Unit Cost (USD)} from the
 * cost field.
 */
public enum ProductField implements ImportFieldSpec {

    ITEM("item", "Item number", true,
            "Your internal SKU. New items are added; known ones are updated.",
            "Item No", "HRD118902", ImportField.ITEM.synonyms()),

    DESCRIPTION("description", "Description", true,
            "Required for a new item. For a known item, blank keeps the existing description.",
            "Description", "1/2 IN COPPER TYPE L HARD TUBE 10FT", ImportField.DESCRIPTION.synonyms()),

    CATEGORY("category", "Category", false,
            "Top-level grouping (Plumbing, HVAC). Drives filters, insights and the benchmark bands.",
            "Category", "Plumbing",
            List.of("category", "productcategory", "itemcategory", "productgroup", "itemgroup", "prodcat",
                    "productline", "itemclass", "matkl", "materialgroup", "producttype", "family",
                    "department", "dept")),

    SUBCATEGORY("subcategory", "Subcategory", false,
            "Second-level grouping (Pipe & tube, Valves).",
            "Subcategory", "Pipe & tube",
            List.of("subcategory", "subcat", "subgroup", "productsubcategory", "itemsubcategory",
                    "productsubgroup", "subclass", "subline", "category2")),

    COMMODITY("commodity", "Commodity", false,
            "The underlying material (copper, pvc, steel). Links the item to the commodity trend.",
            "Commodity", "copper",
            List.of("commodity", "commoditygroup", "commoditycode", "material", "materialtype", "metal",
                    "rawmaterial")),

    LIST_PRICE("listPrice", "List price", false,
            "Your current selling price. Becomes the price list; blank leaves it to sales history.",
            "List Price", "27.10",
            List.of("listprice", "sellprice", "sellingprice", "salesprice", "retailprice", "baseprice",
                    "listprc", "baseprc", "variantprice", "unitprice")),

    UNIT_COST("unitCost", "Unit cost", false,
            "Your current cost. The wizard prices from it when there is no history.",
            "Unit Cost", "19.85",
            List.of("unitcost", "stdcost", "standardcost", "costprice", "avgcost", "averagecost", "currentcost",
                    "landedcost", "costperitem", "variantcost", "stndcost", "purchasecost")),

    ON_HAND("onHand", "On hand", false,
            "Stock on hand. Per branch when a Branch is given, else at your main branch.",
            "On Hand", "1840",
            List.of("onhand", "qtyonhand", "quantityonhand", "stockonhand", "soh", "stockqty", "inventory",
                    "inventoryqty", "available", "qtyavailable", "freestock", "qtyinstock", "instock", "labst",
                    "variantinventoryqty")),

    BRANCH("branch", "Branch", false,
            "The branch a price or stock figure belongs to. Blank means tenant-wide.",
            "Branch", "100959",
            concat(ImportField.BRANCH.synonyms(), List.of("locationcode", "siteid", "plant", "werks", "lgort"))),

    SUPPLIER("supplier", "Supplier", false,
            "Preferred supplier for the item. Unknown names are added to your panel.",
            "Supplier", "Cascade Copper Mills",
            concat(PurchaseField.SUPPLIER.synonyms(),
                    List.of("preferredsupplier", "primarysupplier", "primaryvendor", "supplierac"))),

    SUPPLIER_COST("supplierCost", "Supplier cost", false,
            "The supplier's ex-works price for the item - your buy-side quote.",
            "Supplier Cost", "19.40",
            List.of("suppliercost", "vendorcost", "exworks", "exw", "purchaseprice", "buyprice", "lastcost",
                    "lastpurchasecost", "vendorprice", "supplierprice", "quotedprice")),

    LEAD_TIME("leadTime", "Lead time (days)", false,
            "Whole days from order to delivery for this supplier and item, 0-365.",
            "Lead Time", "17",
            List.of("leadtime", "leadtimedays", "leaddays", "leadtimeindays", "vendorleadtime",
                    "supplierleadtime", "leadtmavg", "replenishmentdays", "deliverydays")),

    UOM("uom", "Unit of measure", false,
            "The selling unit (each, coil, 10 ft length).",
            "UOM", "10 ft length",
            List.of("uom", "unitofmeasure", "sellingunit", "unitofsale", "baseunitofmeasure", "sellunit",
                    "packunit", "meins", "um"));

    private static final Map<String, ProductField> BY_KEY = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ProductField::key, Function.identity()));

    private final String key;
    private final String label;
    private final boolean required;
    private final String hint;
    private final String header;
    private final String example;
    private final List<String> synonyms;

    ProductField(String key, String label, boolean required, String hint, String header, String example,
            List<String> synonyms) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.hint = hint;
        this.header = header;
        this.example = example;
        this.synonyms = synonyms;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        for (String s : b) {
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
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
    public static ProductField from(String key) {
        return Optional.ofNullable(key)
                .map(k -> BY_KEY.get(k.strip()))
                .or(() -> Optional.ofNullable(key).map(k -> byLowerKey(k.strip())))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown products field '" + key + "'. Expected one of " + BY_KEY.keySet()));
    }

    private static ProductField byLowerKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return Stream.of(values())
                .filter(f -> f.key.toLowerCase(Locale.ROOT).equals(lower))
                .findFirst()
                .orElse(null);
    }

    public static List<ProductField> required(java.util.Collection<? extends ImportFieldSpec> present) {
        return java.util.Arrays.stream(values())
                .filter(ProductField::required)
                .filter(field -> !present.contains(field))
                .toList();
    }

    public static String normalise(String header) {
        return ImportFieldSpec.normalise(header);
    }
}
