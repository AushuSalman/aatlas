package com.aatlas.suppliers.internal.csv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The sixteen columns a supplier panel export can carry.
 *
 * <p>A port of {@code SUPPLIER_FIELDS} in the frontend's {@code src/lib/intel/supplier-csv.ts},
 * synonyms and wording included. The import modal shows these labels and hints while the
 * user corrects a mapping, so a second set of names here would mean the screen and the
 * server disagreeing about what a column is called.
 *
 * <p>Four are required, and they are the four the rating is computed from: who they are,
 * where they ship from, how long they take, and how often they are on time. Everything else
 * improves the profile, but the panel can rank without it.
 *
 * <p>Declaration order is load-bearing - {@link SupplierColumnMapping#detect} claims columns
 * in this order and never reassigns one, which is what stops the optional {@code city} from
 * taking a column {@code website} also answers to.
 */
public enum SupplierField {

    NAME("name", "Supplier name", true,
            "The company as you refer to it on a purchase order.",
            List.of("name", "supplier", "suppliername", "vendor", "vendorname", "company",
                    "companyname", "account")),

    COUNTRY("country", "Country", true,
            "Where they ship from. It sets the lane, the duty and the currency they quote in.",
            List.of("country", "origin", "countryoforigin", "shipfrom", "nation", "territory")),

    LEAD_TIME_DAYS("leadTimeDays", "Lead time, days", true,
            "Order to their gate. Inbound transit is added by the platform.",
            List.of("leadtime", "leadtimedays", "leaddays", "lead", "leadtimeindays",
                    "deliverydays", "transitdays")),

    OTIF_PCT("otifPct", "On-time delivery, %", true,
            "Share of orders that arrived when promised, 1-100.",
            List.of("otif", "otifpct", "ontime", "ontimepct", "ontimedelivery", "ontimepercent",
                    "delivery", "servicelevel", "fillrate")),

    PRICE_INDEX("priceIndex", "Price vs market", false,
            "100 is market. 94 is six per cent under. Assumed to be market when missing.",
            List.of("priceindex", "price", "pricevsmarket", "priceindexvsmarket", "pricelevel",
                    "priceposition", "index")),

    DEFECT_PCT("defectPct", "Defect rate, %", false,
            "Share of delivered lots rejected on inspection. Drives the quality star.",
            List.of("defect", "defectpct", "defectrate", "rejectrate", "rejectpct", "ppmdefect",
                    "quality", "qualitypct", "scrappct")),

    COMMUNICATION("communication", "Communication, stars", false,
            "1-5. The one thing no number in the system measures, so it is asked for.",
            List.of("communication", "communicationstars", "comms", "responsiveness", "service",
                    "servicerating")),

    CATEGORY("category", "Category", false,
            "One of the platform's supplier categories.",
            List.of("category", "productcategory", "commodity", "commoditygroup", "type",
                    "segment", "family")),

    CITY("city", "City", false,
            "Where they ship from, for the profile.",
            List.of("city", "town", "location", "site", "plant")),

    WEBSITE("website", "Website", false,
            "Domain is enough; http:// is stripped.",
            List.of("website", "web", "url", "domain", "homepage", "site")),

    YEARS_TRADING("yearsTrading", "Years trading", false,
            "How long they have been in business.",
            List.of("yearstrading", "years", "yearsinbusiness", "founded", "established", "age")),

    CERTIFICATIONS("certifications", "Certifications", false,
            "Separated by semicolons or commas - ISO 9001; PED 2014/68/EU.",
            List.of("certifications", "certification", "certs", "accreditations", "standards",
                    "approvals")),

    HOLDS_STOCK("holdsStock", "Holds stock", false,
            "Yes / no. Whether they can ship from stock rather than make to order.",
            List.of("holdsstock", "stock", "instock", "fromstock", "stocked", "stockholding",
                    "exstock")),

    CONTACT_NAME("contactName", "Contact", false,
            "Who you deal with there.",
            List.of("contact", "contactname", "salesrep", "rep", "accountmanager", "primarycontact")),

    CONTACT_EMAIL("contactEmail", "Contact email", false,
            "Where an RFQ would be sent.",
            List.of("email", "contactemail", "emailaddress", "mail", "salesemail", "rfqemail")),

    CONTACT_PHONE("contactPhone", "Contact phone", false,
            "Direct line, for when a delivery is late.",
            List.of("phone", "contactphone", "telephone", "tel", "mobile", "phonenumber"));

    private static final Map<String, SupplierField> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(f -> f.key.toLowerCase(Locale.ROOT), Function.identity()));

    private final String key;
    private final String label;
    private final boolean required;
    private final String hint;
    private final List<String> synonyms;

    SupplierField(String key, String label, boolean required, String hint, List<String> synonyms) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.hint = hint;
        this.synonyms = synonyms;
    }

    /** camelCase on the wire, matching {@code SupplierFieldKey} in the frontend. */
    @JsonValue
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public boolean required() {
        return required;
    }

    public String hint() {
        return hint;
    }

    public List<String> synonyms() {
        return synonyms;
    }

    @JsonCreator
    public static SupplierField from(String key) {
        return Optional.ofNullable(key)
                .map(k -> BY_KEY.get(k.strip().toLowerCase(Locale.ROOT)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown supplier field '" + key + "'. Expected one of " + BY_KEY.keySet()));
    }

    static List<SupplierField> requiredButAbsent(Collection<SupplierField> present) {
        return Arrays.stream(values()).filter(SupplierField::required).filter(f -> !present.contains(f)).toList();
    }

    /** Header comparison form: lower-cased, letters and digits only. */
    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.toLowerCase(Locale.ROOT).chars()
                .filter(Character::isLetterOrDigit)
                .forEach(c -> out.append((char) c));
        return out.toString();
    }
}
