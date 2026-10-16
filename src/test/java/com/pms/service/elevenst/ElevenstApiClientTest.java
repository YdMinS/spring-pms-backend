package com.pms.service.elevenst;

import com.pms.config.ElevenstProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** FEATURE_2610_13: key header · EUC-KR body · dev stop (D12) · 11st message on failure (D60) · key-use stamp (D88). */
@ExtendWith(MockitoExtension.class)
class ElevenstApiClientTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    @Mock private ElevenstKeyUsageRecorder keyUsageRecorder;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY-1").id(7L).build();
    private ElevenstProperties properties;
    private MockRestServiceServer server;
    private ElevenstApiClient client;

    @BeforeEach
    void setUp() {
        properties = new ElevenstProperties();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ElevenstApiClient(builder, properties, keyUsageRecorder);
    }

    @Test
    void post_sendsKeyHeaderAndEucKrBody_andStampsKeyUse() {
        server.expect(requestTo("https://api.11st.co.kr/rest/prodservices/product"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("openapikey", "KEY-1"))
                .andExpect(content().bytes("<?xml version=\"1.0\" encoding=\"euc-kr\"?><Product>상품</Product>"
                        .getBytes(EUC_KR)))
                .andRespond(withSuccess("<ClientMessage><resultCode>200</resultCode></ClientMessage>"
                        .getBytes(EUC_KR), MediaType.APPLICATION_XML));

        byte[] body = client.post("/rest/prodservices/product", "<Product>상품</Product>", account);

        assertThat(new String(body, EUC_KR)).contains("<resultCode>200</resultCode>");
        verify(keyUsageRecorder).touch(7L);
        server.verify();
    }

    @Test
    void outboundDisabled_failsWithoutNetworkCall() {
        properties.setOutboundEnabled(false);

        assertThatThrownBy(() -> client.get("/rest/areaservice/outboundarea", account))
                .isInstanceOf(BusinessException.class)
                .hasMessage("개발 서버에서는 11번가를 부르지 않습니다");
        verify(keyUsageRecorder, never()).touch(any());
        server.verify();   // no request was expected → none was made
    }

    @Test
    void httpError_becomes502WithElevenstMessage() {
        server.expect(requestTo("https://api.11st.co.kr/rest/prodservices/product"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("<ClientMessage><message>1일 등록 한도 초과</message></ClientMessage>".getBytes(EUC_KR)));

        assertThatThrownBy(() -> client.post("/rest/prodservices/product", "<Product/>", account))
                .isInstanceOf(BusinessException.class)
                .hasMessage("11번가 응답 오류(HTTP 400): 1일 등록 한도 초과")
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(keyUsageRecorder, never()).touch(any());
    }

    /** FEATURE_2610_17 / D39 ②: an already encoded path is sent as is (no second encoding of %). */
    @Test
    void getPreEncoded_keepsThePercentEncoding() {
        server.expect(requestTo("https://api.11st.co.kr/rest/claimservice/returnreqreject/O1/1/C1/101/%BB%F3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("<ResultOrder><result_code>0</result_code></ResultOrder>".getBytes(EUC_KR),
                        MediaType.APPLICATION_XML));

        client.getPreEncoded("/rest/claimservice/returnreqreject/O1/1/C1/101/%BB%F3", account);

        server.verify();
    }
}
