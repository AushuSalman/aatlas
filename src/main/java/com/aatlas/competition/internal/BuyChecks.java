package com.aatlas.competition.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code buy_market_checks}: the last Retail and bulk check per item, as the page showed it.
 *
 * <p>eBay's API licence lets us keep its listings for 24 hours at most. Past that they are taken out
 * of the stored check ({@link #expireEbay}, run hourly by {@link EbayRetention}) and never served
 * ({@link #latest} strips them on read too, so a late or disabled job cannot show one). The eBay
 * run stays, empty, marked {@code expired} with a note to search again; the side's summary stays.
 */

@Repository
class BuyChecks {

    private static final Logger log = LoggerFactory.getLogger(BuyChecks.class);

    private final JdbcTemplate jdbc;
    /** How long eBay listings may be kept and shown. */
    static final Duration EBAY_MAX_AGE = Duration.ofHours(24);
    static final String EBAY_EXPIRED = "eBay listings are removed after 24 hours under eBay's terms - search again to see them.";

    private final ObjectMapper json;

    BuyChecks(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Replaces the item's last check. A check that cannot be written is logged, never failed - it was shown already. */
    @Transactional
    void save(UUID tenantId, UUID productId, CompetitionDtos.BuyCheck check) {
        try {
            jdbc.update("""
                    insert into buy_market_checks (tenant_id, product_id, query, checked_at, result)
                    values (?, ?, ?, ?, cast(? as jsonb))
                    on conflict (tenant_id, product_id) do update
                        set query = excluded.query, checked_at = excluded.checked_at, result = excluded.result,
                            updated_at = now()
                    """, tenantId, productId, check.retail().query(), Timestamp.from(check.checkedAt()),
                    json.writeValueAsString(check));
        } catch (Exception ex) {
            log.warn("buy check for product {} not kept: {}", productId, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    Optional<CompetitionDtos.BuyCheck> latest(UUID tenantId, UUID productId, Instant now) {
        List<String> rows = jdbc.queryForList(
                "select result::text from buy_market_checks where tenant_id = ? and product_id = ?", String.class,
                tenantId, productId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode tree = readTree(rows.get(0));
            Instant checkedAt = json.treeToValue(tree.path("checkedAt"), Instant.class);
            if (checkedAt.isBefore(now.minus(EBAY_MAX_AGE))) {
                stripEbay(tree);
            }
            return Optional.of(json.treeToValue(tree, CompetitionDtos.BuyCheck.class));
        } catch (Exception ex) {
            // A shape from an older release: treat as no check rather than fail the page.
            log.warn("stored buy check for product {} unreadable: {}", productId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Takes eBay listings out of every stored check fetched before {@code now - 24h}. Returns how
     * many checks were rewritten. Runs as the tenant; only checks still holding an eBay listing are read.
     */
    @Transactional
    int expireEbay(UUID tenantId, Instant now) {
        record Row(UUID productId, String result) {
        }
        List<Row> rows = jdbc.query("""
                select product_id, result::text as result from buy_market_checks
                 where tenant_id = ? and checked_at < ?
                   and (jsonb_path_exists(result, '$.retail.providers[*] ? (@.key == "ebay" && @.listings.size() > 0)')
                     or jsonb_path_exists(result, '$.bulk.providers[*] ? (@.key == "ebay" && @.listings.size() > 0)'))
                """, (rs, i) -> new Row(rs.getObject("product_id", UUID.class), rs.getString("result")),
                tenantId, java.sql.Timestamp.from(now.minus(EBAY_MAX_AGE)));
        int rewritten = 0;
        for (Row row : rows) {
            try {
                JsonNode tree = readTree(row.result());
                if (stripEbay(tree)) {
                    jdbc.update("update buy_market_checks set result = cast(? as jsonb), updated_at = now() "
                            + "where tenant_id = ? and product_id = ?", json.writeValueAsString(tree), tenantId,
                            row.productId());
                    rewritten++;
                }
            } catch (Exception ex) {
                // Unreadable: drop the check whole rather than keep eBay data past its limit.
                jdbc.update("delete from buy_market_checks where tenant_id = ? and product_id = ?", tenantId,
                        row.productId());
                rewritten++;
            }
        }
        return rewritten;
    }

    /** Prices read as BigDecimal, not double, so a money value comes back exactly as stored. */
    private JsonNode readTree(String text) throws java.io.IOException {
        return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(text);
    }

    /** Empties every eBay run in both sides and marks it expired; true when anything was removed. */
    static boolean stripEbay(JsonNode check) {
        boolean changed = false;
        for (String side : List.of("retail", "bulk")) {
            for (JsonNode run : check.path(side).path("providers")) {
                if ("ebay".equals(run.path("key").asText()) && run instanceof ObjectNode o
                        && !"expired".equals(o.path("status").asText())) {
                    o.putArray("listings");
                    o.put("status", "expired");
                    o.put("message", EBAY_EXPIRED);
                    changed = true;
                }
            }
        }
        return changed;
    }
}
