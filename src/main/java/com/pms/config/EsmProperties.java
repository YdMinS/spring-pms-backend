package com.pms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ESM (Gmarket · Auction) Trading API settings (application.yml {@code oklyx.esm.*}, FEATURE_2610_26).
 */
@Component
@ConfigurationProperties(prefix = "oklyx.esm")
@Getter
@Setter
public class EsmProperties {

    /** Trading API host. Paths in {@code EsmPaths} are appended to this. */
    private String baseUrl = "https://sa2.esmplus.com";

    /**
     * D10: false = every ESM call fails at once with 「개발 서버에서는 G마켓 · 옥션을 부르지 않습니다」 and never leaves
     * the server. The dev and local profiles set false. There is no exception (category lookups included).
     */
    private boolean outboundEnabled = true;
}
