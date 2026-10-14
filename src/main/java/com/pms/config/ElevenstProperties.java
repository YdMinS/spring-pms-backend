package com.pms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 11st Open API settings (application.yml {@code oklyx.elevenst.*}, FEATURE_2610_13).
 */
@Component
@ConfigurationProperties(prefix = "oklyx.elevenst")
@Getter
@Setter
public class ElevenstProperties {

    /** Seller API host. Paths in {@code ElevenstApiClient} are appended to this. */
    private String baseUrl = "https://api.11st.co.kr";

    /**
     * D12: false = every keyed 11st call fails at once with 「개발 서버에서는 11번가를 부르지 않습니다」 and never
     * leaves the server. The dev and local profiles set false (D116). The public category download does not use this client.
     */
    private boolean outboundEnabled = true;
}
