package com.pms.service.inquiry;

import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstPaths;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FEATURE_2610_17 / D16 · D26 · D44 · D45 · D50 · D51: Q&A and urgent-notice rows → records, status calls. */
class ElevenstInquiryAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);

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
        ElevenstInquiryAdapter adapter = new ElevenstInquiryAdapter(api, mock(InquiryUpserter.class),
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
    }

    @Test
    void urgentStatusTable_D44() {
        assertThat(ElevenstInquiryAdapter.urgentStatus("02")).isEqualTo(InquiryStatus.UNANSWERED);
        assertThat(ElevenstInquiryAdapter.urgentStatus("05")).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(ElevenstInquiryAdapter.urgentStatus("06")).isEqualTo(InquiryStatus.CLOSED);
        assertThat(ElevenstInquiryAdapter.urgentStatus("99")).isEqualTo(InquiryStatus.ANSWERED);
    }
}
