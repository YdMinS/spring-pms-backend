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

/** FEATURE_2610_17 / D16 · D44 · D45 · D68: 1885, 6982 and 6734 bodies, success codes and failure wording. */
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

    // ---- FEATURE_2610_17 / D68 ----

    @Test
    void nonDelivery_resend_sendsTheParcelFieldsToTheOrderLinePath() {
        CustomerInquiry report = CustomerInquiry.builder().inquiryType(InquiryType.NON_DELIVERY)
                .externalInquiryId("N1").externalOrderId("O1").externalItemId("2").build();
        given(client.put(eq("/rest/nondeliverys/nondeliverystatus/O1/2"), anyString(), eq(account))).willReturn(
                "<Result><resultCode>0</resultCode><resultMessage>정상</resultMessage></Result>".getBytes(EUC_KR));

        adapter.reply(account, report, new InquiryReplyAdapter.ReplyCommand("오늘 다시 보냈습니다", null, "102",
                "00034", "123456789012"));

        verify(client).put("/rest/nondeliverys/nondeliverystatus/O1/2", "<NonDelivery><nonDlvSeq>N1</nonDlvSeq>"
                + "<sellerDtlsRsn>102</sellerDtlsRsn><sellerDtlsCont>오늘 다시 보냈습니다</sellerDtlsCont>"
                + "<dlvMthdCd>01</dlvMthdCd><dlvEtprsCd>00034</dlvEtprsCd><invcNo>123456789012</invcNo>"
                + "</NonDelivery>", account);
    }

    @Test
    void nonDelivery_refusal_isAReplyFailureWithResultMessage() {
        CustomerInquiry report = CustomerInquiry.builder().inquiryType(InquiryType.NON_DELIVERY)
                .externalInquiryId("N1").externalOrderId("O1").externalItemId("2").build();
        given(client.put(anyString(), anyString(), any())).willReturn(
                "<Result><resultCode>-1</resultCode><resultMessage>처리할 수 없는 상태입니다</resultMessage></Result>"
                        .getBytes(EUC_KR));

        assertThatThrownBy(() -> adapter.reply(account, report,
                new InquiryReplyAdapter.ReplyCommand("통화했습니다", null, "101", null, null)))
                .isInstanceOf(InquiryReplyFailedException.class)
                .hasMessage("처리할 수 없는 상태입니다");
    }
}
