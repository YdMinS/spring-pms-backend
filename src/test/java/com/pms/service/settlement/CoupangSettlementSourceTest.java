package com.pms.service.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementType;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.settlement.coupang.CoupangSettlementSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 쿠팡 정산 어댑터 — 매출내역(페이징 가드 · 31일 창 분할 · 방어적 파싱) + 지급내역(최상위 배열 · 조정 분해).
 *
 * 쿠팡 호출은 {@link CoupangApiClient} 목이다(HTTP 없음).
 */
@ExtendWith(MockitoExtension.class)
class CoupangSettlementSourceTest {

    @Mock private CoupangApiClient coupangApiClient;

    private final CoupangProperties coupangProperties = new CoupangProperties();
    private CoupangSettlementSource source;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("V1", null)
            .id(7L).platform(Platform.COUPANG).build();

    @BeforeEach
    void setUp() {
        source = new CoupangSettlementSource(coupangApiClient, coupangProperties, new ObjectMapper());
    }

    @Test
    void fetchRevenuePagesUntilTokenExhausted() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(page("T2", true, line("O1", "V10")))
                .willReturn(page("", false, line("O2", "V20")));

        List<SettlementLineDraft> drafts = collect(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

        verify(coupangApiClient, times(2)).get(anyString(), anyString(), any());
        assertThat(drafts).hasSize(2);
        assertThat(drafts).extracting(SettlementLineDraft::externalOrderId).containsExactly("O1", "O2");
    }

    @Test
    void stopsWhenNextTokenRepeats() {
        // 토큰 정체 = 무한루프 신호. 같은 토큰이 다시 오면 hasNext 가 true 여도 멈춘다.
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(page("T2", true, line("O1", "V10")))
                .willReturn(page("T2", true, line("O2", "V20")));

        List<SettlementLineDraft> drafts = collect(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

        verify(coupangApiClient, times(2)).get(anyString(), anyString(), any());
        assertThat(drafts).hasSize(2);
    }

    @Test
    void splitsWindowOver31Days() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(page("", false));

        collect(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 14));      // 45일

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(2)).get(anyString(), queries.capture(), any());
        for (String query : queries.getAllValues()) {
            LocalDate from = LocalDate.parse(param(query, "recognitionDateFrom"));
            LocalDate to = LocalDate.parse(param(query, "recognitionDateTo"));
            assertThat(ChronoUnit.DAYS.between(from, to) + 1).isLessThanOrEqualTo(31);
        }
        // 첫 페이지의 token 은 생략이 아니라 빈 문자열이다.
        assertThat(queries.getAllValues().get(0)).endsWith("&token=");
    }

    @Test
    void acceptsTopLevelArrayEnvelope() {
        // 쿠팡 문서가 틀린 전례가 있어 data 래핑과 최상위 배열을 모두 받아들인다.
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn("[" + line("O1", "V10") + "]");

        List<SettlementLineDraft> drafts = collect(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2));

        verify(coupangApiClient, times(1)).get(anyString(), anyString(), any());
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).saleType()).isEqualTo(SaleType.SALE);
        assertThat(drafts.get(0).serviceFeeRatio()).isNotNull();
    }

    @Test
    void refundLineIsDetectedFromSaleType() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(page("", false,
                        "{\"orderId\":\"O9\",\"vendorItemId\":\"V90\",\"saleType\":\"REFUND\","
                                + "\"recognitionDate\":\"2026-08-05\",\"saleAmount\":\"1000\"}"));

        List<SettlementLineDraft> drafts = collect(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

        assertThat(drafts.get(0).saleType()).isEqualTo(SaleType.REFUND);
    }

    private List<SettlementLineDraft> collect(LocalDate from, LocalDate to) {
        List<SettlementLineDraft> all = new ArrayList<>();
        source.fetchRevenue(account, from, to, all::addAll);
        return all;
    }

    private static String param(String query, String key) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(key + "=")) {
                return pair.substring(key.length() + 1);
            }
        }
        return "";
    }

    private static String page(String nextToken, boolean hasNext, String... lines) {
        return "{\"code\":200,\"message\":\"OK\",\"data\":[" + String.join(",", lines) + "],"
                + "\"hasNext\":" + hasNext + ",\"nextToken\":\"" + nextToken + "\"}";
    }

    private static String line(String orderId, String vendorItemId) {
        return "{\"orderId\":\"" + orderId + "\",\"vendorItemId\":\"" + vendorItemId + "\","
                + "\"saleType\":\"SALE\",\"recognitionDate\":\"2026-08-05\",\"saleDate\":\"2026-08-01\","
                + "\"quantity\":\"2\",\"saleAmount\":\"10000\",\"serviceFee\":\"1060\","
                + "\"serviceFeeVat\":\"106\",\"serviceFeeRatio\":\"10.6\",\"taxType\":\"TAX\","
                + "\"sellerDiscountCoupon\":\"500\",\"downloadableCoupon\":\"300\","
                + "\"deliveryFee\":\"0\",\"settlementAmount\":\"8834\","
                + "\"settlementDate\":\"2026-08-20\"}";
    }

    // ---- 지급내역(settlement-histories) ----

    @Test
    void fetchPayoutsParsesTopLevelArrayAndKeepsElementsSeparate() {
        // 🔴 매출내역과 달리 최상위가 배열이다. 같은 파서를 쓰면 data 를 찾다가 0건으로 조용히 지나간다.
        //    같은 인식월의 원소를 합치지도 않는다(D5-3).
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn("""
                [{"settlementType":"WEEKLY","settlementDate":"2026-09-04",
                  "revenueRecognitionYearMonth":"2026-08","revenueRecognitionDateFrom":"2026-08-01",
                  "revenueRecognitionDateTo":"2026-08-07","totalSale":1000000,"serviceFee":106000,
                  "finalAmount":880000,"status":"DONE"},
                 {"settlementType":"ADDITIONAL","settlementDate":"2026-09-08",
                  "revenueRecognitionYearMonth":"2026-08","finalAmount":12000,"status":"SUBJECT"}]""");

        List<SettlementPayoutDraft> drafts = source.fetchPayouts(account, YearMonth.of(2026, 8));

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).settlementType()).isEqualTo(SettlementType.WEEKLY);
        assertThat(drafts.get(0).status()).isEqualTo(SettlementPayoutStatus.PAID);
        assertThat(drafts.get(0).recognitionFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(drafts.get(1).settlementType()).isEqualTo(SettlementType.ADDITIONAL);
        assertThat(drafts.get(1).status()).isEqualTo(SettlementPayoutStatus.SCHEDULED);
    }

    @Test
    void fetchPayoutsMapsAdjustmentsAndKeepsUnknownAmountsAsOther() {
        // 값은 전부 양수로 오고 부호는 타입이 결정한다. 모르는 금액 필드는 버리지 않고 OTHER 로 남긴다(D8).
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn("""
                [{"settlementType":"MONTHLY","settlementDate":"2026-09-15",
                  "revenueRecognitionYearMonth":"2026-08","finalAmount":880000,"status":"DONE",
                  "deductionAmount":85000,"debtOfLastWeek":12000,"pendingReleasedAmount":500000,
                  "mysteryFee":7000}]""");

        List<SettlementAdjustmentDraft> adjustments =
                source.fetchPayouts(account, YearMonth.of(2026, 8)).get(0).adjustments();

        assertThat(adjustments).extracting(SettlementAdjustmentDraft::type)
                .containsExactly(SettlementAdjustmentType.DEDUCTION, SettlementAdjustmentType.DEBT_CARRIED,
                        SettlementAdjustmentType.PENDING_RELEASE, SettlementAdjustmentType.OTHER);
        assertThat(adjustments.get(0).amount()).isEqualByComparingTo("85000");   // 양수 그대로
        assertThat(adjustments.get(3).note()).contains("mysteryFee");
    }

    @Test
    void fetchPayoutsUsesTheSettlementHistoriesPathWithTheRecognitionMonth() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn("[]");

        source.fetchPayouts(account, YearMonth.of(2026, 8));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).get(path.capture(), query.capture(), any());
        // ⚠️ 게이트웨이 경로가 매출내역과 다르다(marketplace_openapi) — 오타가 아니다.
        assertThat(path.getValue()).isEqualTo(coupangProperties.getSettlementHistoriesPath());
        assertThat(path.getValue()).contains("marketplace_openapi");
        assertThat(query.getValue()).contains("revenueRecognitionYearMonth=2026-08");
    }
}
