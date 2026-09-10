package com.aatlas.ingest.internal;

import com.aatlas.catalog.CatalogSeeding;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.event.DomainEventPublisher;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.ingest.DataSourceView;
import com.aatlas.ingest.SampleDataConnected;
import com.aatlas.ingest.SampleDataProvisioner;
import com.aatlas.tenant.CountryCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The data-source lifecycle: list, connect, disconnect. Implements the module's public
 * {@link SampleDataProvisioner}.
 *
 * <p>Connecting the sample dataset is the one path that does real work: the seeded
 * catalogue is copied in through {@link CatalogSeeding}, the source row is written, and
 * {@link SampleDataConnected} goes to the outbox - all in one transaction. ERP and
 * warehouse sources are recorded as {@code pending}; the connectors that make them real
 * are step 5 of the build order.
 */
@Service
class DataSourceService implements SampleDataProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    static final String SAMPLE_LABEL = "Sample dataset";
    static final String SAMPLE_DETAIL = "Demo account — seeded history";

    private final DataSourceRepository sources;
    private final CatalogSeeding catalog;
    private final TenantCountryLookup tenantCountry;
    private final DomainEventPublisher events;
    private final AatlasClock clock;

    DataSourceService(
            DataSourceRepository sources,
            CatalogSeeding catalog,
            TenantCountryLookup tenantCountry,
            DomainEventPublisher events,
            AatlasClock clock) {
        this.sources = sources;
        this.catalog = catalog;
        this.tenantCountry = tenantCountry;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<DataSourceView> list() {
        UUID tenantId = TenantContext.requireTenantId();
        return sources.findByTenantIdOrderByConnectedAtDesc(tenantId).stream()
                .map(DataSourceEntity::toView)
                .toList();
    }

    /** {@link SampleDataProvisioner#current}: the newest connection, for any tenant id given. */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<DataSourceView> current(UUID tenantId) {
        return sources.findByTenantIdOrderByConnectedAtDesc(tenantId).stream().findFirst().map(DataSourceEntity::toView);
    }

    /** The REST entry point: routes by kind for the signed-in tenant. */
    @Transactional
    DataSourceView connect(ConnectDataSourceRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID userId = TenantContext.currentUserId().orElse(null);

        return switch (request.kind()) {
            case SAMPLE -> connectSampleData(tenantId, userId);
            case CSV -> throw ApiException.badRequest("use_imports",
                    "CSV history is uploaded with POST /api/v1/imports, which records the source for you.");
            case ERP, WAREHOUSE -> recordPending(tenantId, userId, request);
        };
    }

    @Override
    @Transactional
    public DataSourceView connectSampleData(UUID tenantId, UUID connectedBy) {
        // Advisory: the partial unique index is what decides under concurrency. Checking
        // first turns the ordinary double-click into a clean 409 rather than a rolled-back
        // seed and a constraint violation to unpick.
        sources.findFirstByTenantIdAndKind(tenantId, DataSourceKind.SAMPLE)
                .ifPresent(existing -> {
                    throw alreadyConnected(existing.getId());
                });

        CountryCode country = tenantCountry.countryOf(tenantId);
        CatalogSeeding.SeedSummary seeded = catalog.seedSampleCatalogue(tenantId, country);

        Instant now = clock.now();
        DataSourceEntity source = new DataSourceEntity(
                tenantId,
                DataSourceKind.SAMPLE,
                SAMPLE_LABEL,
                SAMPLE_DETAIL,
                null,
                null,
                DataSourceStatus.CONNECTED,
                now,
                now,
                connectedBy);
        try {
            source = sources.saveAndFlush(source);
        } catch (DataIntegrityViolationException ex) {
            // Two requests raced past the check above; the index caught the loser and the
            // seed it wrote rolls back with it.
            log.debug("Sample data source lost the race on data_sources_sample_uk", ex);
            UUID winner = sources.findFirstByTenantIdAndKind(tenantId, DataSourceKind.SAMPLE)
                    .map(DataSourceEntity::getId)
                    .orElse(null);
            throw alreadyConnected(winner);
        }

        events.publish(new SampleDataConnected(tenantId, source.getId(), now));

        log.info("Sample dataset connected: tenant={} source={} country={} seeded={}",
                tenantId, source.getId(), country, seeded);
        return source.toView();
    }

    @Transactional
    void disconnect(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        DataSourceEntity source = sources.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Data source", id));
        // The catalogue stays: disconnecting a source is a statement about future syncs,
        // not a request to forget what was already learned.
        sources.delete(source);
        log.info("Data source disconnected: tenant={} source={} kind={}", tenantId, id, source.getKind());
    }

    private DataSourceView recordPending(UUID tenantId, UUID userId, ConnectDataSourceRequest request) {
        String label = request.label() == null ? "" : request.label().strip();
        if (label.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "The request body is not valid.",
                    Map.of("fields", Map.of("label", "Name the system you are connecting.")));
        }
        String detail = request.detail() == null || request.detail().isBlank()
                ? "Connection pending"
                : request.detail().strip();

        DataSourceEntity source = sources.save(new DataSourceEntity(
                tenantId,
                request.kind(),
                label,
                detail,
                request.config(),
                request.schedule() == null || request.schedule().isBlank() ? null : request.schedule().strip(),
                DataSourceStatus.PENDING,
                clock.now(),
                null,
                userId));
        log.info("Data source recorded as pending: tenant={} source={} kind={}", tenantId, source.getId(),
                source.getKind());
        return source.toView();
    }

    private static ApiException alreadyConnected(UUID existingId) {
        return new ApiException(HttpStatus.CONFLICT, "already_connected",
                "The sample dataset is already connected to this workspace.",
                existingId == null ? Map.of() : Map.of("dataSourceId", existingId));
    }
}
