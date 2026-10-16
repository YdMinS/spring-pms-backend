package com.pms.service.elevenst;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17: list rows by key field · negative result_code = 502 · D30 split · action result check. */
@ExtendWith(MockitoExtension.class)
class ElevenstOrderApiTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 8, 0, 0);

    @Mock private ElevenstApiClient client;
    @InjectMocks private ElevenstOrderApi api;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    private static byte[] xml(String body) {
        return ("<?xml version=\"1.0\" encoding=\"euc-kr\"?>" + body).getBytes(EUC_KR);
    }

    @Test
    void listByMinute_readsEachRowByKeyField_andFillsTheWindow() {
        given(client.get("/rest/ordservices/complete/202610010000/202610080000", account)).willReturn(xml(
                "<ns2:orders xmlns:ns2=\"http://skt.tmall.business.openapi.spring.service.client.domain/\">"
                        + "<ns2:order><ordNo>201</ordNo><ordPrdSeq>1</ordPrdSeq><rcvrNm>김철수</rcvrNm></ns2:order>"
                        + "<ns2:order><ordNo>201</ordNo><ordPrdSeq>2</ordPrdSeq></ns2:order>"
                        + "</ns2:orders>"));

        List<Map<String, String>> rows =
                api.listByMinute(account, ElevenstPaths.ORDERS_PAID, "ordPrdSeq", FROM, TO);

        assertThat(rows).extracting(r -> r.get("ordPrdSeq")).containsExactly("1", "2");
        assertThat(rows.get(0)).containsEntry("rcvrNm", "김철수");
    }

    @Test
    void negativeResultCode_isA502WithElevenstText() {
        given(client.get(any(), eq(account))).willReturn(xml(
                "<ns2:orders xmlns:ns2=\"x\"><result_code>-3105</result_code>"
                        + "<result_text>조회기간은 최대 7일입니다.</result_text></ns2:orders>"));

        assertThatThrownBy(() -> api.listByMinute(account, ElevenstPaths.ORDERS_PAID, "ordPrdSeq", FROM, TO))
                .isInstanceOf(BusinessException.class)
                .hasMessage("조회기간은 최대 7일입니다. (-3105)");
    }

    @Test
    void zeroResultCode_isAnEmptyList() {
        given(client.get(any(), eq(account))).willReturn(xml(
                "<ns2:orders xmlns:ns2=\"x\"><result_code>0</result_code>"
                        + "<result_text>조회된 결과가 없습니다</result_text></ns2:orders>"));

        assertThat(api.listByMinute(account, ElevenstPaths.ORDERS_PAID, "ordPrdSeq", FROM, TO)).isEmpty();
    }

    @Test
    void negativeResultCode_withNoDataText_isAnEmptyList() {
        given(client.get(any(), eq(account))).willReturn(xml(
                "<s2:orders xmlns:s2=\"x\"><result_code>-1</result_code>"
                        + "<result_text>해당건이 없습니다.</result_text></s2:orders>"));

        assertThat(api.listByMinute(account, ElevenstPaths.CANCEL_REQUESTS, "ordPrdCnSeq", FROM, TO)).isEmpty();
    }

    @Test
    void fullList_isSplitInHalf_D30() {
        StringBuilder full = new StringBuilder("<orders>");
        for (int i = 0; i < ElevenstOrderApi.MAX_ROWS; i++) {
            full.append("<order><ordNo>").append(i).append("</ordNo><ordPrdSeq>1</ordPrdSeq></order>");
        }
        full.append("</orders>");
        given(client.get("/rest/ordservices/complete/202610010000/202610080000", account))
                .willReturn(xml(full.toString()));
        given(client.get("/rest/ordservices/complete/202610010000/202610041200", account))
                .willReturn(xml("<orders><order><ordNo>A</ordNo><ordPrdSeq>1</ordPrdSeq></order></orders>"));
        given(client.get("/rest/ordservices/complete/202610041200/202610080000", account))
                .willReturn(xml("<orders><order><ordNo>B</ordNo><ordPrdSeq>1</ordPrdSeq></order></orders>"));

        List<Map<String, String>> rows =
                api.listByMinute(account, ElevenstPaths.ORDERS_PAID, "ordPrdSeq", FROM, TO);

        assertThat(rows).extracting(r -> r.get("ordNo")).containsExactly("A", "B");
    }

    @Test
    void fullList_belowTheSplitFloor_isKeptAsIs() {
        StringBuilder full = new StringBuilder("<orders>");
        for (int i = 0; i < ElevenstOrderApi.MAX_ROWS; i++) {
            full.append("<order><ordNo>").append(i).append("</ordNo><ordPrdSeq>1</ordPrdSeq></order>");
        }
        full.append("</orders>");
        given(client.get(any(), eq(account))).willReturn(xml(full.toString()));

        List<Map<String, String>> rows = api.listByMinute(account, ElevenstPaths.ORDERS_PAID, "ordPrdSeq",
                FROM, FROM.plusMinutes(90));

        assertThat(rows).hasSize(ElevenstOrderApi.MAX_ROWS);
        verify(client, times(1)).get(any(), eq(account));
    }

    @Test
    void requireResult_passesListedCodes_andFailsOthersWithText() {
        byte[] ok = xml("<ResultOrder><result_code>0</result_code><result_text>정상</result_text></ResultOrder>");
        byte[] already = xml("<ResultOrder><result_code>-3206</result_code>"
                + "<result_text>이미 발주 처리 되었거나 발주 처리할 건이 없음</result_text></ResultOrder>");

        assertThat(ElevenstOrderApi.requireResult(ok, Set.of("0"))).isEqualTo("0");
        assertThat(ElevenstOrderApi.requireResult(already, Set.of("0", "-3206"))).isEqualTo("-3206");
        assertThatThrownBy(() -> ElevenstOrderApi.requireResult(already, Set.of("0")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 발주 처리 되었거나 발주 처리할 건이 없음 (-3206)");
    }
}
