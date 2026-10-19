package com.pms.service.settlement.elevenst;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SaleType;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstXml;
import com.pms.service.settlement.SettlementLineDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 11st settlement list → neutral drafts (FEATURE_2610_20 / D5 · D7 · D16 · D27 · D28 · D33) and the window / result
 * code rules of the call.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstSettlementSourceTest {

    private static final BigDecimal VAT = new BigDecimal("0.1");

    @Mock private ElevenstApiClient client;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(9L).build();

    @Test
    void saleRowMapsToNeutralDraft() {
        SettlementLineDraft draft = ElevenstSettlementSource.toDraft(Map.ofEntries(
                Map.entry("ordNo", "E1"), Map.entry("ordPrdSeq", "2"), Map.entry("ordQty", "3"),
                Map.entry("pocnfrmDt", "20261012"), Map.entry("ordStlEndDt", "20261005"),
                Map.entry("stlPlnDy", "2026/10/20"),
                Map.entry("selPrc", "30,000"), Map.entry("optAmt", "1000"),
                Map.entry("selFee", "3410"), Map.entry("selFixedFee", "11.00%"),
                Map.entry("cupnAmt", "500"), Map.entry("sellerCupnAmt", "200"),
                Map.entry("dlvAmt", "3000"), Map.entry("stlAmt", "27590"),
                Map.entry("memNm", "홍길동")), VAT);

        assertThat(draft.externalOrderId()).isEqualTo("E1");
        assertThat(draft.platformOptionId()).isEqualTo("2");
        assertThat(draft.saleType()).isEqualTo(SaleType.SALE);
        assertThat(draft.recognitionDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(draft.saleDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(draft.settlementDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(draft.quantity()).isEqualTo(3);
        assertThat(draft.saleAmount()).isEqualByComparingTo("31000");
        // D7: 3410 ÷ 1.1 = 3100 · VAT 310 · 11.00% ÷ 100 ÷ 1.1 = 0.1000
        assertThat(draft.serviceFee()).isEqualByComparingTo("3100");
        assertThat(draft.serviceFeeVat()).isEqualByComparingTo("310");
        assertThat(draft.serviceFeeRatio()).isEqualByComparingTo("0.1000");
        assertThat(draft.couponAmount()).isEqualByComparingTo("700");
        assertThat(draft.deliveryFeeAmount()).isEqualByComparingTo("3000");
        assertThat(draft.settlementAmount()).isEqualByComparingTo("27590");
        // D16: member name is not a kept field.
        Map<?, ?> kept = (Map<?, ?>) draft.raw();
        assertThat(kept.containsKey("memNm")).isFalse();
        assertThat(kept.get("ordNo")).isEqualTo("E1");
    }

    @Test
    void claimRowIsRefundWithPositiveAmounts() {
        SettlementLineDraft draft = ElevenstSettlementSource.toDraft(Map.of(
                "ordNo", "E1", "ordPrdSeq", "2", "clmReqSeq", "77", "pocnfrmDt", "20261012",
                "selPrc", "-30000", "selFee", "-3300", "stlAmt", "-26700"), VAT);

        assertThat(draft.saleType()).isEqualTo(SaleType.REFUND);
        assertThat(draft.platformOptionId()).isEqualTo("2-77");
        assertThat(draft.saleAmount()).isEqualByComparingTo("30000");
        assertThat(draft.serviceFee()).isEqualByComparingTo("3000");
        assertThat(draft.serviceFeeVat()).isEqualByComparingTo("300");
        assertThat(draft.settlementAmount()).isEqualByComparingTo("26700");
    }

    @Test
    void negativeSettlementWithoutClaimIsRefund() {
        SettlementLineDraft draft = ElevenstSettlementSource.toDraft(Map.of(
                "ordNo", "E1", "ordPrdSeq", "2", "pocnfrmDt", "20261012", "stlAmt", "-500"), VAT);

        assertThat(draft.saleType()).isEqualTo(SaleType.REFUND);
        assertThat(draft.platformOptionId()).isEqualTo("2");
        assertThat(draft.settlementAmount()).isEqualByComparingTo("500");
    }

    @Test
    void rangeLongerThan31DaysIsSplit() {
        given(client.get(anyString(), any())).willReturn(xml("<result_code>0</result_code>"));

        ElevenstSettlementSource source = new ElevenstSettlementSource(client, VAT);
        source.fetchRevenue(account, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 10), page -> { });

        verify(client).get("/rest/settlement/settlementList/20260901/20261001", account);
        verify(client).get("/rest/settlement/settlementList/20261002/20261010", account);
    }

    @Test
    void rowsBecomeOnePage() {
        given(client.get(anyString(), any())).willReturn(xml(
                "<ns2:seStlDtl><ordNo>E1</ordNo><ordPrdSeq>1</ordPrdSeq><pocnfrmDt>20261012</pocnfrmDt>"
                        + "<stlAmt>9000</stlAmt></ns2:seStlDtl>"
                        + "<ns2:seStlDtl><ordNo>E2</ordNo><ordPrdSeq>1</ordPrdSeq><pocnfrmDt>20261012</pocnfrmDt>"
                        + "<stlAmt>8000</stlAmt></ns2:seStlDtl>"));
        List<List<SettlementLineDraft>> pages = new ArrayList<>();

        new ElevenstSettlementSource(client, VAT)
                .fetchRevenue(account, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), pages::add);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0)).extracting(SettlementLineDraft::externalOrderId).containsExactly("E1", "E2");
    }

    @Test
    void maintenanceCodeWithoutRowsIs502() {
        given(client.get(anyString(), any())).willReturn(xml(
                "<result_code>1000</result_code><result_text>서버 점검중</result_text>"));

        assertThatThrownBy(() -> new ElevenstSettlementSource(client, VAT)
                .fetchRevenue(account, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), page -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("서버 점검중")
                .hasMessageContaining("1000");
    }

    private static byte[] xml(String body) {
        return ElevenstXml.document("<ns2:seStlDtlList xmlns:ns2=\"http://skt.tmall.business.openapi.spring.service"
                + ".client.domain/\">" + body + "</ns2:seStlDtlList>");
    }
}
