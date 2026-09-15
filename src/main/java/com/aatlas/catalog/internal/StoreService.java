package com.aatlas.catalog.internal;

import com.aatlas.catalog.internal.reference.ReferenceDataRepository;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.web.CursorPage;
import com.aatlas.tenant.CountryCode;
import com.aatlas.tenant.TenantDirectory;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branches, read and written.
 *
 * <p>The whole of {@code /api/v1/stores} lives here rather than half in
 * {@link CatalogService}: the rules that decide what a branch may look like - which
 * country, which market region, which subdivision - are the same whether a branch is
 * being created, corrected or listed, and splitting reads from writes would put the
 * placement rule on one side of the line and the query that depends on it on the other.
 *
 * <p>Two invariants are worth naming because everything else follows from them:
 *
 * <ul>
 *   <li><b>The branch code is permanent.</b> {@code sales_transactions.branch_code} and
 *       {@code products.default_store_code} hold it as text with no foreign key behind
 *       them, so a rename here silently orphans history elsewhere.
 *   <li><b>A region is never guessed.</b> It comes from the subdivision through the
 *       reference tables, or the branch opens {@code unassigned} - a real state that keeps
 *       it out of the regional rollups and shows on screen as work to do. A plausible
 *       wrong region is the failure nobody audits.
 * </ul>
 */
@Service
@Transactional(readOnly = true)
class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);

    /** The four market regions, plus the one that means "nobody has placed this yet". */
    private static final Set<String> REGIONS =
            Set.of("south", "west", "north", "east", StoreEntity.UNASSIGNED_REGION);

    /** {@code stores_segment_ck}. */
    private static final Set<String> SEGMENTS = Set.of("regular", "occasional");

    /** {@code stores_anchor_ck}: which side of the dot the label sits on. */
    private static final Set<String> ANCHORS = Set.of("start", "end");

    private final StoreRepository stores;
    private final ProductStoreRepository productStores;
    private final ReferenceDataRepository reference;
    private final TenantDirectory tenants;
    private final StoreAccess access;

    StoreService(
            StoreRepository stores,
            ProductStoreRepository productStores,
            ReferenceDataRepository reference,
            TenantDirectory tenants,
            StoreAccess access) {
        this.stores = stores;
        this.productStores = productStores;
        this.reference = reference;
        this.tenants = tenants;
        this.access = access;
    }

    // ---- reads -------------------------------------------------------------------------

    CursorPage<StoreView> list(String region, String q, Boolean active, int limit, String cursor) {
        UUID tenantId = Catalogues.requireCatalogue(stores);
        String after = Cursors.decode(cursor);

        Specification<StoreEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantId"), tenantId));
            if (region != null && !region.isBlank()) {
                where.add(cb.equal(root.get("regionKey"), region.strip().toLowerCase(Locale.ROOT)));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + Catalogues.escapeLike(q.strip().toLowerCase(Locale.ROOT)) + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("storeCode")), pattern, '\\'),
                        cb.like(cb.lower(root.get("legalName")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("msaName"), "")), pattern, '\\')));
            }
            if (active != null) {
                where.add(cb.equal(root.get("active"), active));
            }
            if (after != null) {
                where.add(cb.greaterThan(root.get("storeCode"), after));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        List<StoreView> rows = stores
                .findBy(spec, fetch -> fetch.sortBy(Sort.by("storeCode")).limit(limit + 1).all())
                .stream()
                .map(StoreView::of)
                .toList();
        return CursorPage.of(rows, limit, row -> Cursors.encode(row.storeId()));
    }

    StoreView get(String idOrCode) {
        return StoreView.of(find(Catalogues.requireCatalogue(stores), idOrCode));
    }

    // ---- writes ------------------------------------------------------------------------

    /**
     * Opens a branch.
     *
     * <p>Deliberately not behind the {@code no_catalogue} gate the reads sit behind: a
     * company that has connected nothing yet still has branches, and typing the first one
     * in is a legitimate way to start a catalogue rather than an error to route around.
     */
    @Transactional
    StoreView create(CreateStoreRequest request) {
        access.requireHeadOrDirector();
        UUID tenantId = TenantContext.requireTenantId();

        String storeCode = request.storeId().strip();
        if (stores.existsByTenantIdAndStoreCode(tenantId, storeCode)) {
            throw ApiException.conflict("store_code_taken",
                    "Branch " + storeCode + " already exists. Branch codes are unique per company.");
        }

        CountryCode country = countryFor(tenantId, request.country());
        Placement placement = place(country, request.state(), request.state() != null, request.regionKey());
        MapPosition map = mapPosition(request.map());

        StoreEntity store = new StoreEntity(
                tenantId,
                storeCode,
                text(request.companyNumber()),
                request.legalName().strip(),
                country.name(),
                placement.subdivisionCode(),
                text(request.msaName()),
                request.rpp(),
                request.txns(),
                request.itemCount(),
                segment(request.segment()),
                placement.regionKey(),
                map.x(),
                map.y(),
                map.anchor(),
                StoreEntity.Source.MANUAL);
        if (Boolean.FALSE.equals(request.active())) {
            store.setActive(false);
        }

        StoreEntity saved = save(store, storeCode);
        log.info("Branch {} created in tenant {} by user {}", storeCode, tenantId,
                TenantContext.currentUserId().orElse(null));
        return StoreView.of(saved);
    }

    /** Corrects a branch. Absent fields keep what is stored; the code never moves. */
    @Transactional
    StoreView patch(String idOrCode, PatchStoreRequest request) {
        access.requireHeadOrDirector();
        UUID tenantId = TenantContext.requireTenantId();
        StoreEntity store = find(tenantId, idOrCode);

        if (request.storeId() != null && !request.storeId().strip().equals(store.getStoreCode())) {
            throw ApiException.badRequest("store_code_immutable",
                    "A branch code is what the ERP and twelve months of transactions call this branch, "
                            + "so it cannot be changed. Create the new branch and retire this one instead.");
        }

        // A country the caller did not touch is taken as it stands rather than re-checked
        // against its siblings, for the same reason the subdivision below is.
        CountryCode country = request.country() == null
                ? country(store.getCountry())
                : countryFor(tenantId, request.country());

        // A subdivision the caller did not touch is not re-validated: the seed and older
        // imports are allowed to hold codes the reference tables no longer list, and an
        // edit to the MSA should not fail because of one.
        boolean stateSupplied = request.state() != null;
        String stateIn = stateSupplied ? request.state() : store.getSubdivisionCode();
        boolean stateChanged = stateSupplied && !normaliseCode(request.state()).equals(store.getSubdivisionCode());
        // When the state moves and the caller says nothing about the region, the region
        // follows it. Keeping the old one would only ever produce a mismatch error.
        String regionIn = request.regionKey() != null
                ? request.regionKey()
                : (stateChanged ? null : store.getRegionKey());
        Placement placement = place(country, stateIn, stateSupplied, regionIn);

        MapPosition map = request.map() == null
                ? new MapPosition(store.getMapX(), store.getMapY(), store.getMapAnchor())
                : mapPosition(request.map());

        store.apply(
                request.companyNumber() == null ? store.getCompanyNumber() : text(request.companyNumber()),
                request.legalName() == null ? store.getLegalName() : request.legalName().strip(),
                country.name(),
                placement.subdivisionCode(),
                request.msaName() == null ? store.getMsaName() : text(request.msaName()),
                request.rpp() == null ? store.getRpp() : request.rpp(),
                request.txns() == null ? store.getTxns() : request.txns(),
                request.itemCount() == null ? store.getItemCount() : request.itemCount(),
                request.segment() == null ? store.getSegment() : segment(request.segment()),
                placement.regionKey(),
                map.x(),
                map.y(),
                map.anchor(),
                request.active() == null ? store.isActive() : request.active());

        return StoreView.of(save(store, store.getStoreCode()));
    }

    /**
     * Closes a branch for good.
     *
     * <p>Refuses while the branch has item-at-branch history, because the delete would
     * cascade it away ({@code product_stores} is {@code ON DELETE CASCADE}) and every
     * (item, branch) pair it made priceable would quietly stop being priceable. The honest
     * answer for a branch that traded is {@code PATCH {"active": false}}, which retires it
     * and keeps the record; {@code force=true} is for the caller who means it.
     */
    @Transactional
    void delete(String idOrCode, boolean force) {
        access.requireHeadOrDirector();
        UUID tenantId = TenantContext.requireTenantId();
        StoreEntity store = find(tenantId, idOrCode);

        long history = productStores.countByTenantIdAndStoreId(tenantId, store.getId());
        if (history > 0 && !force) {
            throw new ApiException(HttpStatus.CONFLICT, "store_in_use",
                    "Branch %s has sales history for %d items. Retire it with PATCH {\"active\": false} to "
                            .formatted(store.getStoreCode(), history)
                            + "keep that history, or repeat this call with ?force=true to delete both.",
                    Map.of("storeId", store.getStoreCode(), "itemsWithHistory", history));
        }

        stores.delete(store);
        log.info("Branch {} deleted from tenant {} by user {} (history rows removed: {})",
                store.getStoreCode(), tenantId, TenantContext.currentUserId().orElse(null), history);
    }

    // ---- placement ---------------------------------------------------------------------

    /** A branch's country, subdivision and market region, resolved and agreed with each other. */
    private record Placement(String subdivisionCode, String regionKey) {
    }

    private record MapPosition(Integer x, Integer y, String anchor) {
    }

    /**
     * Which country this branch trades in.
     *
     * <p>The company's, and it has to be: {@code GET /regions} picks the map and the whole
     * regional vocabulary from the country its branches are in, so a second country in one
     * catalogue would not read as two markets - it would read as one market with half its
     * branches missing.
     */
    private CountryCode countryFor(UUID tenantId, String requested) {
        CountryCode existing = stores.findCountries(tenantId).stream().sorted().findFirst()
                .map(StoreService::country)
                .orElseGet(() -> tenants.get(tenantId).country());
        if (requested == null || requested.isBlank()) {
            return existing;
        }
        CountryCode asked = country(requested);
        if (asked != existing) {
            throw ApiException.badRequest("country_mismatch",
                    "This company's branches are in " + existing + ". A branch in " + asked
                            + " would need its own workspace.");
        }
        return asked;
    }

    private static CountryCode country(String value) {
        try {
            return CountryCode.from(value);
        } catch (IllegalArgumentException unknown) {
            throw ApiException.badRequest("unknown_country", "\"" + value + "\" is not a country this "
                    + "platform trades in. Use US or UK.");
        }
    }

    /**
     * The subdivision and the market region, agreed.
     *
     * <p>A subdivision decides the region. A region sent alongside one that disagrees is a
     * conflict rather than an override: one of the two is wrong and the caller is the only
     * one who knows which.
     */
    private Placement place(CountryCode country, String subdivisionIn, boolean subdivisionSupplied, String regionIn) {
        String subdivision = normaliseCode(subdivisionIn);
        Map<String, String> regionBySubdivision = regionsBySubdivision(country);

        String derived = subdivision == null ? null : regionBySubdivision.get(subdivision);
        if (subdivision != null && derived == null && subdivisionSupplied) {
            throw ApiException.badRequest("unknown_subdivision",
                    "\"" + subdivision + "\" is not a " + country + " "
                            + (country == CountryCode.UK ? "region" : "state") + " code.");
        }

        String asked = regionIn == null || regionIn.isBlank() ? null : regionIn.strip().toLowerCase(Locale.ROOT);
        if (asked != null && !REGIONS.contains(asked)) {
            throw ApiException.badRequest("unknown_region",
                    "\"" + asked + "\" is not a market region. Use south, west, north, east, or "
                            + "unassigned while nobody has placed the branch.");
        }
        if (asked != null && derived != null && !asked.equals(derived)) {
            throw ApiException.badRequest("region_mismatch",
                    subdivision + " is in the " + derived + " region, not " + asked
                            + ". Send one of the two, or correct whichever is wrong.");
        }

        if (asked != null) {
            return new Placement(subdivision, asked);
        }
        return new Placement(subdivision, derived == null ? StoreEntity.UNASSIGNED_REGION : derived);
    }

    /** Subdivision code to market region, for one country. Reference data, cached upstream. */
    private Map<String, String> regionsBySubdivision(CountryCode country) {
        Map<String, String> byCode = new LinkedHashMap<>();
        for (ReferenceDataRepository.MarketRegion region : reference.regionsOf(country.name())) {
            for (String code : region.codes()) {
                byCode.put(code.toUpperCase(Locale.ROOT), region.key());
            }
        }
        return byCode;
    }

    // ---- helpers -----------------------------------------------------------------------

    private StoreEntity find(UUID tenantId, String idOrCode) {
        return Catalogues.<StoreEntity>findByCodeOrId(idOrCode,
                        code -> stores.findByTenantIdAndStoreCode(tenantId, code),
                        id -> stores.findByTenantIdAndId(tenantId, id))
                .orElseThrow(() -> ApiException.notFound("Store", idOrCode));
    }

    /**
     * Saves, turning the unique index on (tenant, branch code) into the 409 it means.
     *
     * <p>The pre-check in {@link #create} catches the ordinary case; this catches two
     * requests for the same new branch arriving at once, where the check passes in both
     * and only one insert can win.
     */
    private StoreEntity save(StoreEntity store, String storeCode) {
        try {
            return stores.saveAndFlush(store);
        } catch (DataIntegrityViolationException clash) {
            throw ApiException.conflict("store_code_taken",
                    "Branch " + storeCode + " already exists. Branch codes are unique per company.");
        }
    }

    private static String segment(String value) {
        String clean = text(value);
        if (clean == null) {
            return null;
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (!SEGMENTS.contains(lower)) {
            throw ApiException.badRequest("unknown_segment",
                    "\"" + clean + "\" is not a branch segment. Use regular or occasional.");
        }
        return lower;
    }

    /**
     * The dot on the map, or nothing.
     *
     * <p>x and y travel together: half a coordinate places the branch on the edge of the
     * canvas rather than leaving it off, which looks like data instead of an omission.
     */
    private static MapPosition mapPosition(CreateStoreRequest.MapInput map) {
        if (map == null || (map.x() == null && map.y() == null && text(map.anchor()) == null)) {
            return new MapPosition(null, null, null);
        }
        if (map.x() == null || map.y() == null) {
            throw ApiException.badRequest("incomplete_map_point",
                    "A map position needs both x and y. Omit map entirely to leave the branch off the map.");
        }
        String anchor = text(map.anchor()) == null ? null : map.anchor().strip().toLowerCase(Locale.ROOT);
        if (anchor != null && !ANCHORS.contains(anchor)) {
            throw ApiException.badRequest("unknown_map_anchor",
                    "\"" + anchor + "\" is not a label anchor. Use start or end.");
        }
        return new MapPosition(map.x(), map.y(), anchor);
    }

    private static String normaliseCode(String value) {
        String clean = text(value);
        return clean == null ? null : clean.toUpperCase(Locale.ROOT);
    }

    /** Trimmed, or null for "the caller sent nothing here". */
    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.strip();
        return clean.isEmpty() ? null : clean;
    }
}
