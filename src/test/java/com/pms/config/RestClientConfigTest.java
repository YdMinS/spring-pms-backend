package com.pms.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * RestClientConfig — 커스터마이저가 만드는 요청 팩토리의 <b>타입</b>을 고정한다 (FEATURE_2609_21 Step 1).
 *
 * <p>이 테스트가 따로 필요한 이유: {@code CoupangApiClientTest} 는 {@code MockRestServiceServer} 를
 * 빌더에 바인딩해 팩토리를 갈아끼우므로, 실제로 어떤 팩토리가 쓰이는지 <b>증명하지 못한다</b>.
 * 기본 폴백(Apache→Jetty→OkHttp→Simple)이 걸리면 {@code SimpleClientHttpRequestFactory} 가 선택돼
 * PATCH 가 {@code ProtocolException} 으로 전송조차 되지 않는데, 그 실패는 전체 테스트가 GREEN 인 채
 * dev 에서만 터진다.
 *
 * <p>{@code RestClient.Builder} 는 팩토리를 노출하지 않으므로 빌더를 목으로 받아 캡처한다.
 * 스프링 컨텍스트는 띄우지 않는다.
 */
class RestClientConfigTest {

    @Test
    void customizer_usesJdkRequestFactory_soPatchIsActuallySent() {
        CoupangProperties properties = new CoupangProperties();     // 타임아웃 기본값 사용
        RestClient.Builder builder = mock(RestClient.Builder.class);

        new RestClientConfig().coupangRestClientCustomizer(properties).customize(builder);

        ArgumentCaptor<ClientHttpRequestFactory> factory =
                ArgumentCaptor.forClass(ClientHttpRequestFactory.class);
        verify(builder).requestFactory(factory.capture());
        assertThat(factory.getValue()).isInstanceOf(JdkClientHttpRequestFactory.class);
    }
}
