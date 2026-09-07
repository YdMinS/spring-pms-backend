package com.pms.service.coupang;

import com.pms.domain.CoupangAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.fixture.MarketplaceAccountFixture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 자격증명 접근 헬퍼 (FEATURE_2609_26 / 02). 쿠팡 호출 52곳이 전부 이 판정을 통과한다.
 */
class CoupangCredentialsTest {

    @Test
    void of_coupangAccountWithCredential_returnsIt() {
        MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A00012345", "wing").build();

        CoupangAccountCredential credential = CoupangCredentials.of(account);

        assertThat(credential.getVendorId()).isEqualTo("A00012345");
        assertThat(credential.getVendorUserId()).isEqualTo("wing");
        assertThat(credential.getAccessKey()).isEqualTo("ak");
        assertThat(credential.getSecretKey()).isEqualTo("sk");
    }

    @Test
    void of_missingCredential_throws400() {
        MarketplaceAccount account = MarketplaceAccountFixture.accountWithoutCredential(Platform.COUPANG);

        assertThatThrownBy(() -> CoupangCredentials.of(account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자격증명 미설정");
    }

    @Test
    void of_naverAccount_throws400() {
        MarketplaceAccount account = MarketplaceAccountFixture.accountWithoutCredential(Platform.NAVER);

        assertThatThrownBy(() -> CoupangCredentials.of(account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("쿠팡 계정이 아닙니다");
    }
}
