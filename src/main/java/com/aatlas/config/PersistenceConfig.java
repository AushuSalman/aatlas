package com.aatlas.config;

import com.aatlas.common.tenant.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Persistence wiring: auditing, and the connection-level tenant binding that makes
 * PostgreSQL row-level security work.
 *
 * <p>The blueprint's rule is "every table carries {@code tenant_id}; RLS is defence in
 * depth, with the tenant set per connection from the JWT". The second half happens here:
 * every connection handed out sets {@code app.tenant_id}, and the RLS policies compare
 * against it. A query that forgets its tenant predicate then returns nothing rather than
 * another company's prices.
 *
 * <p>Boot still builds and configures the Hikari pool; this only decorates it, so
 * {@code spring.datasource.hikari.*} keeps working. It is off until
 * {@code aatlas.rls.enabled=true}, because the wiring should exist before the policies do.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class PersistenceConfig {

    /** Stamps {@code created_by} / {@code updated_by} from the request's JWT. */
    @Bean
    AuditorAware<UUID> auditorAware() {
        return TenantContext::currentUserId;
    }

    @Bean
    @ConditionalOnProperty(name = "aatlas.rls.enabled", havingValue = "true")
    static BeanPostProcessor tenantAwareDataSourceDecorator(
            org.springframework.beans.factory.ObjectProvider<AatlasProperties> properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    String variable = properties.getObject().rls().sessionVariable();
                    return new TenantAwareDataSource(dataSource, variable);
                }
                return bean;
            }
        };
    }

    /**
     * Sets the tenant on each connection as it leaves the pool and clears it on the way
     * back, so a pooled connection can never carry one tenant's id into another tenant's
     * transaction.
     */
    static final class TenantAwareDataSource extends DelegatingDataSource {

        private final String sessionVariable;

        TenantAwareDataSource(DataSource delegate, String sessionVariable) {
            super(delegate);
            this.sessionVariable = sessionVariable;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return bind(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return bind(super.getConnection(username, password));
        }

        private Connection bind(Connection connection) throws SQLException {
            Optional<UUID> tenantId = TenantContext.current().map(TenantContext.Actor::tenantId);
            try (var statement = connection.prepareStatement("select set_config(?, ?, false)")) {
                statement.setString(1, sessionVariable);
                statement.setString(2, tenantId.map(UUID::toString).orElse(""));
                statement.execute();
            }
            return connection;
        }
    }
}
