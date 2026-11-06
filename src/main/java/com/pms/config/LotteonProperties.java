package com.pms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LotteON Open API settings (application.yml {@code oklyx.lotteon.*}, FEATURE_2610_24).
 */
@Component
@ConfigurationProperties(prefix = "oklyx.lotteon")
@Getter
@Setter
public class LotteonProperties {

    /** Open API host. Paths in {@code LotteonPaths} are appended to this. */
    private String baseUrl = "https://openapi.lotteon.com";

    /** Standard category lookup host (205) — a different host, the same key. */
    private String categoryBaseUrl = "https://onpick-api.lotteon.com";

    /**
     * D8: false = every LotteON call fails at once with 「개발 서버에서는 롯데ON 을 부르지 않습니다」 and never leaves the
     * server. The dev and local profiles set false. There is no exception (category lookups included, D10).
     */
    private boolean outboundEnabled = true;
}
