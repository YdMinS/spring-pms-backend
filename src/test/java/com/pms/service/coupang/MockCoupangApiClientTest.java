package com.pms.service.coupang;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockCoupangApiClient(@Profile("local")) 가 ordersheets 경로에 대해 fixture JSON 을 반환하는지 검증.
 * 라이브 호출 없이 classpath fixture 만 읽으므로 순수 단위 테스트로 충분하다(account 는 무시됨 → null).
 */
class MockCoupangApiClientTest {

    private final MockCoupangApiClient client = new MockCoupangApiClient();

    @Test
    void get_ordersheets경로_fixtureJson반환() {
        String body = client.get(
                "/v2/providers/openapi/apis/api/v5/vendors/A00000000/ordersheets", "", null);

        // fixture 가 비어있지 않고, 파서가 읽는 키(data/orderId)를 포함해야 한다.
        assertThat(body).isNotBlank();
        assertThat(body).contains("\"data\"");
        assertThat(body).contains("orderId");
    }

    @Test
    void get_알수없는경로_기본빈응답() {
        String body = client.get("/some/other/path", "", null);

        assertThat(body).contains("\"data\":[]");
    }
}
