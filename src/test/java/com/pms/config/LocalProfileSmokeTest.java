package com.pms.config;

import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.MockCoupangApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * local 프로파일 컨텍스트 로드 smoke 테스트.
 * env 없이 컨텍스트가 뜨고, CoupangApiClient 로 라이브 Impl 이 아닌 MockCoupangApiClient 가 주입되는지 검증.
 */
@SpringBootTest
@ActiveProfiles("local")
class LocalProfileSmokeTest {

    @Autowired
    private CoupangApiClient coupangApiClient;

    @Test
    void 컨텍스트로드_그리고_Mock클라이언트주입() {
        assertThat(coupangApiClient).isInstanceOf(MockCoupangApiClient.class);
    }
}
