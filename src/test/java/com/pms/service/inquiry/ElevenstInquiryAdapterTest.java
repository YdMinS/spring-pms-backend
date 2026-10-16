package com.pms.service.inquiry;

import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstPaths;
import com.pms.service.elevenst.ElevenstTimes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FEATURE_2610_17 / D16 · D26 · D44 · D45 · D50 · D51 · D68 · D69: Q&A, urgent-notice and non-delivery rows →
 * records, status calls.
 */
class ElevenstInquiryAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);
    private static final byte[] EMPTY_NON_DELIVERY =
            "<ns0:nonDeliverys xmlns:ns0=\"http://skt.tmall.business.openapi.spring.service.client.domain/\">"
                    .concat("<ns0:resultCode>0</ns0:resultCode></ns0:nonDeliverys>")
                    .getBytes(Charset.forName("EUC-KR"));

    @Test
    void qna_answered_carriesTheSellerAnswerAsReply() {
        InquiryRecord record = ElevenstInquiryAdapter.qna(Map.of("brdInfoNo", "B1", "brdInfoClfNo", "P100",
                "brdInfoSbjct", "사이즈", "brdInfoCont", "몇 cm 인가요?", "answerYn", "Y", "answerCont", "25cm 입니다",
                "createDt", "2026-10-14 10:00:00.0", "ordNoDe", "O1"), NOW);

        assertThat(record.type()).isEqualTo(InquiryType.PRODUCT_QNA);
        assertThat(record.externalProductId()).isEqualTo("P100");
        assertThat(record.externalOrderId()).isEqualTo("O1");
        assertThat(record.status()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(record.content()).isEqualTo("사이즈\n\n몇 cm 인가요?");
        assertThat(record.replies()).extracting(InquiryRecord.ReplyRecord::content).containsExactly("25cm 입니다");
        assertThat(record.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 10, 14, 10, 0));
    }

    @Test
    void urgent_onlyInquiriesAreLoaded_andReRequestIsUnanswered() {
        byte[] xml = ("<alimis><alimi><emerNtceSeq>E1</emerNtceSeq><emerNtceClfNo1>10</emerNtceClfNo1>"
                + "<emerNtceCrntCd>04</emerNtceCrntCd><emerCtnt>재답변 부탁</emerCtnt><createDt>20261014</createDt>"
                + "<emerReplyList><emerReplyCtnt>처리했습니다</emerReplyCtnt></emerReplyList></alimi>"
                + "<alimi><emerNtceSeq>E2</emerNtceSeq><emerNtceClfNo1>11</emerNtceClfNo1></alimi></alimis>")
                .getBytes(Charset.forName("EUC-KR"));
        var rows = ElevenstOrderApi.rowElements(xml, "emerNtceSeq");

        InquiryRecord inquiry = ElevenstInquiryAdapter.urgent(rows.get(0), NOW);
        InquiryRecord notice = ElevenstInquiryAdapter.urgent(rows.get(1), NOW);

        assertThat(notice).isNull();
        assertThat(inquiry.type()).isEqualTo(InquiryType.CALL_CENTER);
        assertThat(inquiry.status()).isEqualTo(InquiryStatus.UNANSWERED);
        assertThat(inquiry.replies()).extracting(InquiryRecord.ReplyRecord::content).containsExactly("처리했습니다");
        assertThat(inquiry.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 10, 14, 0, 0));
    }

    @Test
    void urgentStatuses_quickReadsOpenOnly_nightlyAddsClosedOver30Days() {
        ElevenstOrderApi api = mock(ElevenstOrderApi.class);
        when(api.listByDay(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        ElevenstApiClient client = mock(ElevenstApiClient.class);
        when(client.get(any(), any())).thenReturn(EMPTY_NON_DELIVERY);
        ElevenstInquiryAdapter adapter = new ElevenstInquiryAdapter(api, client, mock(InquiryUpserter.class),
                mock(InquiryStaleSweeper.class));
        MarketplaceAccount account = MarketplaceAccount.builder().id(7L).build();
        LocalDate today = LocalDate.now(SyncWindow.KST);

        adapter.syncInquiries(account, false);
        adapter.syncInquiries(account, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> extra = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(api, times(9)).listByDay(eq(account), eq(ElevenstPaths.ALIMI_LIST), extra.capture(),
                eq("emerNtceSeq"), from.capture(), eq(today));
        assertThat(extra.getAllValues()).extracting(m -> m.get("emerNtceCrntCd"))
                .containsExactly("01", "02", "04", "01", "02", "04", "03", "05", "06");
        assertThat(from.getAllValues().get(0)).isEqualTo(today.minusDays(6));
        assertThat(from.getAllValues().get(8)).isEqualTo(today.minusDays(29));
        String nonDeliveryPath = "/rest/nondeliverys/nondeliverylist/03/"
                + ElevenstTimes.DAY.format(today.minusDays(29)) + "/" + ElevenstTimes.DAY.format(today);
        verify(client, times(2)).get(nonDeliveryPath, account);
    }

    // ---- FEATURE_2610_17 / D68 ----

    @Test
    void nonDelivery_unhandled_isUnansweredWithOurSentence_andNoBuyerContact() {
        Map<String, String> row = new HashMap<>(Map.of("nonDlvSeq", "N1", "ordNo", "O1", "ordPrdSeq", "1",
                "prdNm", "물병", "optNm", "파랑", "ordQty", "2", "sndEndDt", "2026/10/10 13:27:37",
                "dlvEtprsNm", "CJ대한통운", "invcNo", "123456789012", "nonDlvStatNm", "미도착처리중"));
        row.put("nonDlvReqDt", "2026/10/12 09:00:00");
        row.put("rcvrNm", "홍길동");

        InquiryRecord record = ElevenstInquiryAdapter.nonDelivery(row, NOW);

        assertThat(record.type()).isEqualTo(InquiryType.NON_DELIVERY);
        assertThat(record.externalInquiryId()).isEqualTo("N1");
        assertThat(record.externalItemId()).isEqualTo("1");
        assertThat(record.itemName()).isEqualTo("물병 / 파랑");
        assertThat(record.content()).isEqualTo(
                "고객이 상품을 받지 못했다고 신고했습니다. 수량 2개 · 발송 2026/10/10 13:27:37 · CJ대한통운 123456789012");
        assertThat(record.status()).isEqualTo(InquiryStatus.UNANSWERED);
        assertThat(record.platformStatus()).isEqualTo("미도착처리중");
        assertThat(record.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 0));
        assertThat(record.replies()).isEmpty();
    }

    @Test
    void nonDelivery_handledResend_isAnsweredWithOneSellerReply() {
        InquiryRecord record = ElevenstInquiryAdapter.nonDelivery(Map.of("nonDlvSeq", "N2", "ordNo", "O1",
                "ordPrdSeq", "1", "sellerDtlsRsn", "재발송하였습니다", "resendDlvEtprsCdNm", "롯데택배",
                "resendInvcNo", "998877665544", "updateDt", "2026/10/13 10:00:00"), NOW);

        assertThat(record.status()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(record.content()).isEqualTo("고객이 상품을 받지 못했다고 신고했습니다.");
        assertThat(record.answeredAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 10, 0));
        assertThat(record.replies()).extracting(InquiryRecord.ReplyRecord::content)
                .containsExactly("재발송하였습니다 (재발송 롯데택배 998877665544)");
    }

    @Test
    void urgentStatusTable_D44() {
        assertThat(ElevenstInquiryAdapter.urgentStatus("02")).isEqualTo(InquiryStatus.UNANSWERED);
        assertThat(ElevenstInquiryAdapter.urgentStatus("05")).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(ElevenstInquiryAdapter.urgentStatus("06")).isEqualTo(InquiryStatus.CLOSED);
        assertThat(ElevenstInquiryAdapter.urgentStatus("99")).isEqualTo(InquiryStatus.ANSWERED);
    }
}
