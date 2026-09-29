package com.aatlas.competition.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.ingest.ImportCommitted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A product file the tenant uploaded was committed: fetch competitor prices for its products
 * in the background (when the tenant has chosen its sources). Sample data is left alone.
 *
 * <p>Runs on Modulith's worker thread, which carries no tenant, so the event's tenant is bound.
 */
@Component
class ProductsImportedListener {

    private final PriceSourcesService sources;

    ProductsImportedListener(PriceSourcesService sources) {
        this.sources = sources;
    }

    @ApplicationModuleListener
    void on(ImportCommitted event) {
        if (!"products".equals(event.kind()) || !"upload".equals(event.source())) {
            return;
        }
        TenantContext.runAs(TenantContext.Actor.system(event.tenantId()), () -> {
            sources.onProductsImported(event.tenantId(), event.batchId());
            return null;
        });
    }
}
