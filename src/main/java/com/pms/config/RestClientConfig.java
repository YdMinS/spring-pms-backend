package com.pms.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

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
     *
     * ⚠️ The factory type is named explicitly instead of letting {@code ClientHttpRequestFactories.get(settings)}
     * pick one. That fallback probes Apache -> Jetty -> OkHttp -> Simple, and none of the first three are on
     * the classpath, so it lands on SimpleClientHttpRequestFactory (HttpURLConnection) which rejects PATCH
     * outright ({@code ProtocolException: Invalid HTTP method: PATCH}) - the claim actions (FEATURE_2609_21)
     * are 4/7 PATCH. The JDK HttpClient supports arbitrary methods.
     * ⚠️ This failure is invisible to {@code CoupangApiClientTest}: MockRestServiceServer swaps the factory
     * out, so the suite stays green while dev breaks. {@code RestClientConfigTest} guards the type instead.
     */
    @Bean
    public RestClientCustomizer coupangRestClientCustomizer(CoupangProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return builder -> builder.requestFactory(
                ClientHttpRequestFactories.get(JdkClientHttpRequestFactory.class, settings));
    }
}
