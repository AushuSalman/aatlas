/**
 * CSV import, ERP sync and warehouse share pipelines.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "ingest",
        allowedDependencies = {"catalog", "common", "suppliers", "tenant"})
package com.aatlas.ingest;
