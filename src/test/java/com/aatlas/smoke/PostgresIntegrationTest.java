package com.aatlas.smoke;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for anything that needs a real database.
 *
 * <p>A real PostgreSQL, not H2: the schema uses extensions, {@code jsonb}, partitioning
 * and row-level security, so a test against an in-memory imitation would prove only that
 * the migrations apply to something this application never runs on.
 *
 * <p>The container is static and started by Spring Boot's {@code @ServiceConnection}
 * support, so one instance is shared across the whole run rather than started per class.
 * {@code disabledWithoutDocker} skips these tests on a machine with no Docker instead of
 * failing the build, so a developer without Docker Desktop can still run the unit and
 * architecture tests. CI has Docker and runs everything.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aatlas")
            .withUsername("aatlas")
            .withPassword("aatlas");
}
