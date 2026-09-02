package com.pms.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RestClient wiring shared by every auto-configured RestClient.Builder consumer.
 */
@Configuration
public class RestClientConfig {

    /**
     * Applies connect/read timeouts to the auto-configured RestClient.Builder.
     *
     * Timeouts must not be set in CoupangApiClientImpl's constructor: CoupangApiClientTest binds
     * MockRestServiceServer to the builder before constructing the client, so a constructor-side
     * requestFactory(...) would overwrite the mock factory and break that test.
     * The Coupang client is currently the only RestClient consumer; any future one inherits these
     * timeouts by design (no unbounded external call).
     */
    @Bean
    public RestClientCustomizer coupangRestClientCustomizer(CoupangProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }
}
