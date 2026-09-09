package com.aatlas.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.config.AatlasProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The context starts, the migrations apply to a real PostgreSQL, and the infrastructure
 * beans the rest of the build assumes are actually there.
 *
 * <p>Worth having from day one: most of what breaks a Spring application breaks at
 * startup, and this fails in seconds rather than at deploy time.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextIT extends PostgresIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    AatlasClock clock;

    @Autowired
    AatlasProperties properties;

    @Test
    void contextLoadsWithEveryInfrastructureBean() {
        assertThat(dataSource).isNotNull();
        assertThat(cacheManager).isNotNull();
        assertThat(jwtEncoder).isNotNull();
        assertThat(jwtDecoder).isNotNull();
        assertThat(clock).isNotNull();
    }

    @Test
    void configurationBindsFromYaml() {
        assertThat(properties.role()).isEqualTo(AatlasProperties.Role.API);
        assertThat(properties.jwt().accessTokenTtl()).hasMinutes(15);
        assertThat(properties.rls().sessionVariable()).isEqualTo("app.tenant_id");
    }

    /** Time comes from here or nowhere; an ArchUnit rule enforces the rest. */
    @Test
    void clockIsAvailable() {
        assertThat(clock.now()).isNotNull();
        assertThat(clock.today()).isNotNull();
    }

    /** Every cache the code names must exist, or a @Cacheable typo fails silently. */
    @Test
    void namedCachesAreConfigured() {
        assertThat(cacheManager.getCache(com.aatlas.common.cache.CacheNames.OVERVIEW)).isNotNull();
        assertThat(cacheManager.getCache(com.aatlas.common.cache.CacheNames.GUARDRAILS)).isNotNull();
    }
}
