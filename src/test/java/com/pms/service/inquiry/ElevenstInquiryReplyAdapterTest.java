package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.service.elevenst.ElevenstApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D16 · D44 · D45: 1885 and 6982 bodies, success codes and failure wording. */
@ExtendWith(MockitoExtension.class)
class ElevenstInquiryReplyAdapterTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    @Mock private ElevenstApiClient client;
    @InjectMocks private ElevenstInquiryReplyAdapter adapter;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    @Test
    void urgentNotice_sendsTheD44Body() {
        CustomerInquiry urgent = CustomerInquiry.builder().inquiryType(InquiryType.CALL_CENTER)
                .externalInquiryId("E1").build();
        given(client.put(eq("/rest/alimi/alimianswer"), anyString(), eq(account))).willReturn(
                "<alimiResult><result_code>200</result_code></alimiResult>".getBytes(EUC_KR));

        adapter.reply(account, urgent, new InquiryReplyAdapter.ReplyCommand("확인했습니다 & 처리", null));

        verify(client).put("/rest/alimi/alimianswer", "<request><confirmYn>Y</confirmYn><emerNtceSeq>E1</emerNtceSeq>"
                + "<answerCtnt>확인했습니다 &amp; 처리</answerCtnt></request>", account);
    }

    @Test
    void qna_refusal_isAReplyFailureWith11stText() {
        CustomerInquiry qna = CustomerInquiry.builder().inquiryType(InquiryType.PRODUCT_QNA)
                .externalInquiryId("B1").externalProductId("P100").build();
        given(client.put(eq("/rest/prodqnaservices/prodqnaanswer/B1/P100"), anyString(), eq(account))).willReturn(
                "<ClientMessage><resultCode>500</resultCode><message>답변 등록 실패</message></ClientMessage>"
                        .getBytes(EUC_KR));

        assertThatThrownBy(() -> adapter.reply(account, qna, new InquiryReplyAdapter.ReplyCommand("네", null)))
                .isInstanceOf(InquiryReplyFailedException.class)
                .hasMessage("답변 등록 실패");
    }

    @Test
    void devStop_isAReplyFailureWithItsOwnWording_D8() {
        CustomerInquiry qna = CustomerInquiry.builder().inquiryType(InquiryType.PRODUCT_QNA)
                .externalInquiryId("B1").externalProductId("P100").build();
        given(client.put(anyString(), anyString(), any())).willThrow(
                new BusinessException("개발 서버에서는 11번가를 부르지 않습니다", HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.reply(account, qna, new InquiryReplyAdapter.ReplyCommand("네", null)))
                .isInstanceOf(InquiryReplyFailedException.class)
                .hasMessage("개발 서버에서는 11번가를 부르지 않습니다");
    }
}
