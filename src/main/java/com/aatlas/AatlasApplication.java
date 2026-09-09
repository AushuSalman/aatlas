package com.aatlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

/**
 * Aatlas backend: a modular monolith.
 *
 * <p>One deployable, hard module boundaries enforced by Spring Modulith at build time
 * (see {@code ModularityTests}). The compute-heavy work runs from the same artifact
 * started with a different role, so nothing user-facing waits on an engine run:
 *
 * <pre>
 *   aatlas.role=api            serves /api/v1 (default)
 *   aatlas.role=engine-worker  recomputes the snapshot tables
 *   aatlas.role=ingest-worker  CSV imports, ERP syncs, warehouse shares
 * </pre>
 *
 * <p>{@code common} and {@code config} are shared modules: every business module may
 * depend on them, and neither may depend on a business module.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(systemName = "Aatlas", sharedModules = {"common", "config"})
public class AatlasApplication {

    public static void main(String[] args) {
        SpringApplication.run(AatlasApplication.class, args);
    }
}
