package com.aatlas.competition.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The price on a competitor's product page, read the way search engines read it.
 *
 * <p>Almost every store publishes its price as structured data for Google Shopping, so a page
 * need not be understood to be priced. In order of trust:
 * <ol>
 *   <li>JSON-LD {@code Product} ({@code offers} as an object, an array, or an
 *       {@code AggregateOffer} with {@code lowPrice}), anywhere in {@code @graph} too;</li>
 *   <li>Open Graph / Facebook product meta: {@code product:price:amount}, {@code og:price:amount};</li>
 *   <li>microdata: {@code itemprop="price"} with a {@code content} attribute.</li>
 * </ol>
 * Nothing else - a dollar figure scraped from visible text is as likely to be a shipping
 * threshold as a price. A page with none of the three gives null, and the caller says so.
 * Pure: no I/O.
 */
final class ProductPageParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META = Pattern.compile("<meta\\s+[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEMPROP_PRICE = Pattern.compile(
            "<[^>]+itemprop\\s*=\\s*[\"']price[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ProductPageParser() {
    }

    /** @param how which rule found the price: {@code json-ld}, {@code meta} or {@code microdata} */
    record Priced(BigDecimal price, String currency, String title, String how) {
    }

    static Priced parse(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String pageTitle = pageTitle(html);
        Priced ld = jsonLd(html);
        if (ld != null) {
            return new Priced(ld.price(), ld.currency(), ld.title() != null ? ld.title() : pageTitle, ld.how());
        }
        String amount = null;
        String currency = null;
        String ogTitle = null;
        Matcher m = META.matcher(html);
        while (m.find()) {
            String tag = m.group();
            String key = attr(tag, "property");
            if (key == null) {
                key = attr(tag, "name");
            }
            if (key == null) {
                key = attr(tag, "itemprop");
            }
            if (key == null) {
                continue;
            }
            String content = attr(tag, "content");
            switch (key.toLowerCase(Locale.ROOT)) {
                case "product:price:amount", "og:price:amount" -> amount = amount == null ? content : amount;
                case "product:price:currency", "og:price:currency", "pricecurrency" ->
                        currency = currency == null ? content : currency;
                case "og:title" -> ogTitle = content;
                default -> {
                }
            }
        }
        String title = ogTitle != null ? ogTitle : pageTitle;
        BigDecimal price = money(amount);
        if (price != null) {
            return new Priced(price, upper(currency), title, "meta");
        }
        Matcher micro = ITEMPROP_PRICE.matcher(html);
        while (micro.find()) {
            BigDecimal p = money(attr(micro.group(), "content"));
            if (p != null) {
                return new Priced(p, upper(currency), title, "microdata");
            }
        }
        return null;
    }

    // ---- JSON-LD ------------------------------------------------------------------------

    private static Priced jsonLd(String html) {
        Matcher m = LD_JSON.matcher(html);
        while (m.find()) {
            JsonNode root;
            try {
                root = JSON.readTree(m.group(1).strip());
            } catch (Exception ex) {
                continue; // one malformed block never hides the next
            }
            List<JsonNode> nodes = new ArrayList<>();
            collect(root, nodes);
            for (JsonNode n : nodes) {
                if (!isType(n, "Product")) {
                    continue;
                }
                Priced p = offer(n.get("offers"), n.path("name").asText(null));
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> collect(n, out));
        } else if (node.isObject()) {
            out.add(node);
            collect(node.get("@graph"), out);
            collect(node.get("hasVariant"), out); // a ProductGroup's variants are Products
        }
    }

    private static boolean isType(JsonNode n, String type) {
        JsonNode t = n.get("@type");
        if (t == null) {
            return false;
        }
        if (t.isArray()) {
            for (JsonNode x : t) {
                if (type.equalsIgnoreCase(x.asText())) {
                    return true;
                }
            }
            return false;
        }
        return type.equalsIgnoreCase(t.asText());
    }

    private static Priced offer(JsonNode offers, String name) {
        if (offers == null || offers.isNull()) {
            return null;
        }
        if (offers.isArray()) {
            for (JsonNode o : offers) {
                Priced p = offer(o, name);
                if (p != null) {
                    return p;
                }
            }
            return null;
        }
        BigDecimal price = money(text(offers.get("price")));
        if (price == null) {
            price = money(text(offers.get("lowPrice")));
        }
        if (price == null) {
            JsonNode spec = offers.get("priceSpecification");
            if (spec != null) {
                JsonNode first = spec.isArray() ? spec.path(0) : spec;
                price = money(text(first.get("price")));
            }
        }
        if (price == null) {
            return null;
        }
        String currency = text(offers.get("priceCurrency"));
        if (currency == null && offers.has("priceSpecification")) {
            JsonNode spec = offers.get("priceSpecification");
            currency = text((spec.isArray() ? spec.path(0) : spec).get("priceCurrency"));
        }
        return new Priced(price, upper(currency), name, "json-ld");
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE)
                .matcher(tag);
        if (!m.find()) {
            return null;
        }
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    /** "1,234.56", "12.5", "$9.99" - a positive amount, or null. European "1.234,56" is not read. */
    static BigDecimal money(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().replaceAll("[^0-9.,]", "").replace(",", "");
        if (s.isEmpty() || s.chars().filter(c -> c == '.').count() > 1) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(s);
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String pageTitle(String html) {
        Matcher m = TITLE.matcher(html);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").strip() : null;
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }
}
