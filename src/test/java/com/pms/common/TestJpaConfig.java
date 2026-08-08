package com.pms.common;

import com.pms.config.TenantIdentifierResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@TestConfiguration
@EnableJpaAuditing
public class TestJpaConfig {

    /**
     * @DataJpaTest slices do not scan @Component beans, but @TenantId entities now require a
     * CurrentTenantIdentifierResolver or Hibernate fails to build the SessionFactory
     * ("configured for multi-tenancy, but no tenant identifier specified"). Register it here.
     */
    @Bean
    public TenantIdentifierResolver tenantIdentifierResolver() {
        return new TenantIdentifierResolver();
    }
}
