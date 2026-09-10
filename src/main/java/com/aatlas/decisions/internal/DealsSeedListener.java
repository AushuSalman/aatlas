package com.aatlas.decisions.internal;

import com.aatlas.ingest.SampleDataConnected;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Seeds a tenant's 181 historical deals ({@code seed/deals.json}, generated from the
 * frontend's {@code DEALS} fixture - 151 sell + 30 buy) into {@code deal} when the sample data
 * source connects, exactly like {@code suppliers}' {@code SuppliersSeedListener} seeds the
 * supplier panel from the same event. The History screen and Overview's "gained/lost" figures
 * are meaningless without this backdrop.
 *
 * <p>Idempotent: a tenant that already has deals is left alone.
 */
@Component
class DealsSeedListener {

    private static final Logger log = LoggerFactory.getLogger(DealsSeedListener.class);
    private static final String SEED_PATH = "classpath:seed/deals.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedDeal(
            String id, LocalDate date, String side, String itemNumber, String description, String counterparty,
            int qty, BigDecimal cost, BigDecimal baselinePrice, BigDecimal suggestedPrice, BigDecimal actualPrice,
            boolean followed, BigDecimal gain, BigDecimal lost) {
    }

    private final DealRepository deals;
    private final ObjectMapper json;

    DealsSeedListener(DealRepository deals, ObjectMapper json) {
        this.deals = deals;
        this.json = json;
    }

    // No @Transactional here - see ProcurementLedgerSeedListener's javadoc-adjacent comment
    // on the same rule (@ApplicationModuleListener already carries its own).
    @ApplicationModuleListener
    void on(SampleDataConnected event) {
        var tenantId = event.tenantId();
        if (deals.existsByTenantId(tenantId)) {
            log.info("Deals already seeded for tenant {}", tenantId);
            return;
        }
        List<SeedDeal> rows = readSeed();
        List<DealEntity> entities = rows.stream()
                .map(r -> new DealEntity(r.id(), r.side(), r.itemNumber(), r.description(), r.counterparty(), r.qty(),
                        r.cost(), r.baselinePrice(), r.suggestedPrice(), r.actualPrice(), r.followed(),
                        r.gain(), r.lost(), false, null, null, null, null, r.date(), null))
                .toList();
        for (DealEntity e : entities) {
            e.setTenantId(tenantId);
        }
        deals.saveAll(entities);
        log.info("Seeded {} deals for tenant {} ({} sell, {} buy)", entities.size(), tenantId,
                rows.stream().filter(r -> "sell".equals(r.side())).count(),
                rows.stream().filter(r -> "buy".equals(r.side())).count());
    }

    private List<SeedDeal> readSeed() {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(SEED_PATH);
            try (InputStream in = resource.getInputStream()) {
                return json.readValue(in, new TypeReference<List<SeedDeal>>() {
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + SEED_PATH, e);
        }
    }
}
