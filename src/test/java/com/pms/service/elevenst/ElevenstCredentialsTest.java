package com.pms.service.elevenst;

import com.pms.domain.ElevenstAccountCredential;
import com.pms.fixture.MarketplaceAccountFixture;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FEATURE_2610_13 / D5 · D88. */
class ElevenstCredentialsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);

    @Test
    void of_coupangAccount_rejected() {
        assertThatThrownBy(() -> ElevenstCredentials.of(MarketplaceAccountFixture.coupangAccountStub()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가 판매채널이 아닙니다.");
    }

    @Test
    void renewalNeeded_onlyAfter150Days() {
        ElevenstAccountCredential day150 = ElevenstAccountCredential.builder()
                .apiKey("k").keyLastUsedAt(NOW.minusDays(150)).build();
        ElevenstAccountCredential day151 = ElevenstAccountCredential.builder()
                .apiKey("k").keyLastUsedAt(NOW.minusDays(151)).build();

        assertThat(ElevenstCredentials.renewalNeeded(day150, NOW)).isFalse();
        assertThat(ElevenstCredentials.renewalNeeded(day151, NOW)).isTrue();
        assertThat(ElevenstCredentials.renewalNeeded(null, NOW)).isFalse();
    }
}
