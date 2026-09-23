package com.aatlas.prices.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.history.Catalogue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * "Check the market": one item's category, subcategory and description, searched on the
 * open web and read back into a price - the AI price-research pilot.
 *
 * <p>Deliberately not a second AI provider on top of Tavily: {@code include_answer} already
 * asks Tavily's own model to synthesise one line from the search results ("$2-$8 per foot,
 * typically"), and a dollar-amount scan over that line is honest about what it is - a
 * regex over an AI summary, not a verified quote. A customer with a mainstream retail item
 * (a UPC, a branded fixture) gets a real answer; most B2B distributor parts, quoted
 * privately and never published, come back with nothing found rather than a guess.
 */
@Service
class MarketPriceService {

    /** Matches "$1,234.56", "$8", "1,234.56 USD" - a currency figure, comma-grouped or not. */
    private static final Pattern MONEY = Pattern.compile(
            "\\$\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s?(?:USD|usd)");

    private final Catalogue catalogue;
    private final TavilyClient tavily;

    MarketPriceService(Catalogue catalogue, TavilyClient tavily) {
        this.catalogue = catalogue;
        this.tavily = tavily;
    }

    boolean available() {
        return tavily.available();
    }

    MarketPriceView research(String itemNumber) {
        return research(itemNumber, false);
    }

    /**
     * What a supplier would charge for the item, rather than what it sells for.
     *
     * <p>The buy side asks a different question: the sell search reads street and retail listings,
     * which are the wrong benchmark for a purchase. This asks for wholesale, trade and distributor
     * pricing, so the figure can honestly be set beside a supplier's landed quote.
     */
    MarketPriceView researchBuy(String itemNumber) {
        return research(itemNumber, true);
    }

    private MarketPriceView research(String itemNumber, boolean buySide) {
        if (!tavily.available()) {
            throw ApiException.badRequest("market_research_unavailable",
                    "AI market research is not configured for this environment.");
        }
        Catalogue.ProductRef product = catalogue.product(itemNumber)
                .orElseThrow(() -> ApiException.notFound("Item", itemNumber));

        String query = buySide ? buyQuery(product) : query(product);
        TavilyClient.Response res;
        try {
            res = tavily.search(query, 6, true);
        } catch (TavilyClient.TavilySearchFailed ex) {
            return new MarketPriceView(product.itemNumber(), product.description(), query, null, null,
                    "The market could not be searched right now: " + ex.getMessage(), List.of());
        }

        // A web search for a price also returns share prices, company directories and
        // encyclopaedia entries. They are not sellers, and their figures are not this item's -
        // a stock quote read as a unit price moves the median - so they are dropped before
        // anything is read from them or shown as somebody to buy from.
        List<TavilyClient.Result> results = res.results() == null ? List.of()
                : res.results().stream().filter(MarketPriceService::looksLikeASeller).toList();

        List<BigDecimal> found = extractAmounts(res.answer());
        // The answer line is the one thing here written for a human to read in full; if it
        // named no figure, scanning the raw result snippets too is one more honest try
        // before giving up, rather than a second AI call.
        if (found.isEmpty()) {
            for (TavilyClient.Result r : results) {
                found.addAll(extractAmounts(r.content()));
                if (found.size() >= 3) {
                    break;
                }
            }
        }

        List<MarketPriceView.Source> sources = results.stream().limit(6)
                .map(r -> new MarketPriceView.Source(r.title(), r.url()))
                .toList();

        if (found.isEmpty()) {
            return new MarketPriceView(product.itemNumber(), product.description(), query, null, null,
                    res.answer() != null ? res.answer()
                            : buySide
                                    ? "No public wholesale price could be found for this item. Trade pricing is often "
                                            + "quoted rather than listed, so an RFQ is the way to a real number."
                                    : "No public price could be found for this item. This is common for parts quoted "
                                            + "directly to distributors rather than listed publicly.",
                    sources);
        }
        Collections.sort(found);
        BigDecimal suggested = median(found);
        return new MarketPriceView(product.itemNumber(), product.description(), query, suggested, found, res.answer(),
                sources);
    }

    /**
     * Hosts that answer a price search but never sell the thing: markets and financial news,
     * company directories and data brokers, maps and reviews, social networks, encyclopaedias.
     * Matched on the registrable part of the host, so a country subdomain is caught too.
     */
    private static final List<String> NOT_SELLERS = List.of(
            "investing.com", "bloomberg.com", "reuters.com", "marketwatch.com", "yahoo.com", "ft.com",
            "wsj.com", "nasdaq.com", "nyse.com", "moneycontrol.com", "economictimes.com", "tradingview.com",
            "tracxn.com", "crunchbase.com", "zoominfo.com", "dnb.com", "owler.com", "pitchbook.com",
            "mapquest.com", "yelp.com", "yellowpages.com", "bbb.org", "manta.com", "foursquare.com",
            "linkedin.com", "facebook.com", "twitter.com", "x.com", "instagram.com", "reddit.com",
            "youtube.com", "pinterest.com", "quora.com", "wikipedia.org", "wikimedia.org",
            "indeed.com", "glassdoor.com", "ziprecruiter.com");

    /** Titles that are plainly about a company's shares rather than its goods. */
    private static final Pattern NOT_A_PRICE = Pattern.compile(
            "(?i)\\b(share price|stock price|market cap|quarterly results|earnings|annual report|"
                    + "company profile|financials|revenue growth|shareholding)\\b");

    static boolean looksLikeASeller(TavilyClient.Result r) {
        if (r == null || r.url() == null || r.url().isBlank()) {
            return false;
        }
        String host;
        try {
            host = java.net.URI.create(r.url()).getHost();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        for (String blocked : NOT_SELLERS) {
            if (lower.equals(blocked) || lower.endsWith("." + blocked) || lower.contains("." + blocked + ".")) {
                return false;
            }
        }
        return r.title() == null || !NOT_A_PRICE.matcher(r.title()).find();
    }

    /** The buy-side question: what a trade buyer pays, not what a shop charges. */
    private static String buyQuery(Catalogue.ProductRef p) {
        StringBuilder q = new StringBuilder("wholesale trade price per unit for ").append(p.description());
        if (meaningful(p.category())) {
            q.append(' ').append(p.category());
        }
        return q.append(" from distributors or suppliers, bulk pricing").toString();
    }

    private static String query(Catalogue.ProductRef p) {
        StringBuilder q = new StringBuilder("price of ").append(p.description());
        if (meaningful(p.category())) {
            q.append(' ').append(p.category());
        }
        if (meaningful(p.subcategory())) {
            q.append(' ').append(p.subcategory());
        }
        return q.toString();
    }

    /** Not blank, and not the placeholder an import leaves when nothing was mapped to it. */
    private static boolean meaningful(String value) {
        return value != null && !value.isBlank() && !"uncategorised".equalsIgnoreCase(value.strip());
    }

    static List<BigDecimal> extractAmounts(String text) {
        List<BigDecimal> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                BigDecimal v = new BigDecimal(raw.replace(",", ""));
                // Filters out obvious noise a currency-shaped regex still catches: page/story
                // years ("2024"), phone-number-shaped runs, and anything absurd for a unit price.
                if (v.signum() > 0 && v.compareTo(BigDecimal.valueOf(100_000)) < 0) {
                    out.add(v);
                }
            } catch (NumberFormatException ignored) {
                // Not a real number once the commas come out; skip it.
            }
        }
        return out;
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
    }
}
