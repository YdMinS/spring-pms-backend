package com.pms.service.settlement.elevenst;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SaleType;
import com.pms.exception.BusinessException;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstPaths;
import com.pms.service.elevenst.ElevenstTimes;
import com.pms.service.elevenst.ElevenstXml;
import com.pms.service.settlement.SettlementLineDraft;
import com.pms.service.settlement.SettlementPayoutDraft;
import com.pms.service.settlement.SettlementSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.pms.service.settlement.elevenst.ElevenstSettlementRow.date;
import static com.pms.service.settlement.elevenst.ElevenstSettlementRow.money;
import static com.pms.service.settlement.elevenst.ElevenstSettlementRow.sum;
import static com.pms.service.settlement.elevenst.ElevenstSettlementRow.text;

/**
 * 11st settlement lines — guide category 151 「정산내역조회」 (FEATURE_2610_20 / D1 · D5 · D7 · D16 · D27 · D28 · D33).
 *
 * <p>Every call goes through {@link ElevenstApiClient} (dev / local stop with 503, key-use stamp, 502 wording).
 * One row of the list = one 11st order line ({@code ordNo} + {@code ordPrdSeq}); the list is XML (EUC-KR).</p>
 *
 * <ul>
 *   <li>Window: 11st answers at most 31 days per call (result code {@code 2}) → a longer range is split into
 *       31-day windows here; callers pass any range ({@link SettlementSource} rule).</li>
 *   <li>Result code {@code 0} = 「조회된 결과가 없습니다」 = an empty window. Any other code with no rows (e.g.
 *       {@code 2}, {@code 1000} weekly maintenance) → 502 with 11st's text.</li>
 *   <li>D30: 11st has no payout API — {@link #fetchPayouts} returns nothing and is never called while
 *       {@code ElevenstPayoutBuilder} is registered.</li>
 * </ul>
 */
@Component
public class ElevenstSettlementSource implements SettlementSource {

    /** Guide category 151 — settlement list by date range ({@code yyyyMMdd} both ends, inclusive). */
    static final String SETTLEMENT_LIST = "/rest/settlement/settlementList/{start}/{end}";

    /** 11st rejects a longer window with result code 2. */
    static final int WINDOW_MAX_DAYS = 31;

    /** Every row of the list carries this field — rows are found as its parent element. */
    static final String ROW_KEY = "ordNo";

    private final ElevenstApiClient client;
    private final BigDecimal feeVatRate;

    public ElevenstSettlementSource(ElevenstApiClient client,
                                    @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate) {
        this.client = client;
        this.feeVatRate = feeVatRate == null ? BigDecimal.ZERO : feeVatRate;
    }

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    /** One page per 31-day window, earliest window first. */
    @Override
    public void fetchRevenue(MarketplaceAccount account, LocalDate from, LocalDate to,
                             Consumer<List<SettlementLineDraft>> pageConsumer) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("조회 기간(from, to)이 올바르지 않습니다.");
        }
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate end = cursor.plusDays(WINDOW_MAX_DAYS - 1L);
            if (end.isAfter(to)) {
                end = to;
            }
            String path = ElevenstPaths.fill(SETTLEMENT_LIST, Map.of(
                    "start", ElevenstTimes.DAY.format(cursor),
                    "end", ElevenstTimes.DAY.format(end)));
            List<SettlementLineDraft> page = new ArrayList<>();
            for (Map<String, String> row : rows(client.get(path, account))) {
                page.add(toDraft(row, feeVatRate));
            }
            if (!page.isEmpty()) {
                pageConsumer.accept(page);
            }
            cursor = end.plusDays(1);
        }
    }

    /** D30: 11st gives no payout list. Payouts are built from stored lines by {@code ElevenstPayoutBuilder}. */
    @Override
    public List<SettlementPayoutDraft> fetchPayouts(MarketplaceAccount account, YearMonth month) {
        return List.of();
    }

    /**
     * Rows of one list response (direct child fields of each row).
     *
     * @throws BusinessException 502 when the body is not XML, or carries a result code other than {@code 0} and no row
     */
    static List<Map<String, String>> rows(byte[] raw) {
        Document doc = ElevenstXml.parseOrNull(raw);
        if (doc == null) {
            throw new BusinessException("11번가 정산 응답을 읽을 수 없습니다", HttpStatus.BAD_GATEWAY);
        }
        Set<Element> rows = new LinkedHashSet<>();
        for (Element key : ElevenstXml.elements(doc, ROW_KEY)) {
            if (key.getParentNode() instanceof Element parent) {
                rows.add(parent);
            }
        }
        String code = ElevenstXml.firstText(doc, "result_code");
        if (rows.isEmpty() && code != null && !"0".equals(code)) {
            String text = ElevenstXml.firstText(doc, "result_text");
            throw new BusinessException("11번가 정산 조회 실패: " + (text == null ? "" : text) + " (" + code + ")",
                    HttpStatus.BAD_GATEWAY);
        }
        return ElevenstOrderApi.fields(List.copyOf(rows));
    }

    /**
     * One row → neutral draft.
     *
     * <ul>
     *   <li>D33 (provisional): a claim number or a negative {@code stlAmt} = REFUND, and every neutral amount is stored
     *       positive (the direction lives in the sale type).</li>
     *   <li>D5: option key = {@code ordPrdSeq}; a refund row = {@code ordPrdSeq-clmReqSeq} so it never collides with
     *       the sale row of the same order line.</li>
     *   <li>D27: recognition = {@code pocnfrmDt} · sale date = {@code ordStlEndDt} · quantity = {@code ordQty} ·
     *       settlement = {@code stlAmt} · remittance date ({@code stlPlnDy}) → the draft's settlement date.</li>
     *   <li>D28: sale = {@code selPrc + optAmt} · seller coupons = 6 fields · delivery = 7 fields.</li>
     *   <li>D7: {@code selFee} is VAT-inclusive → fee = selFee ÷ (1 + VAT rate) rounded to the won (HALF_UP),
     *       fee VAT = selFee − fee, measured rate = {@code selFixedFee} ÷ 100 ÷ (1 + VAT rate), 4 places HALF_UP.</li>
     * </ul>
     */
    static SettlementLineDraft toDraft(Map<String, String> row, BigDecimal feeVatRate) {
        String ordPrdSeq = text(row, "ordPrdSeq");
        String claim = text(row, "clmReqSeq");
        BigDecimal settlement = money(row, "stlAmt");
        boolean refund = claim != null || (settlement != null && settlement.signum() < 0);
        String optionKey = (ordPrdSeq == null || claim == null) ? ordPrdSeq : ordPrdSeq + "-" + claim;

        BigDecimal divisor = BigDecimal.ONE.add(feeVatRate);
        BigDecimal selFee = positive(money(row, "selFee"), refund);
        BigDecimal fee = selFee == null ? null : selFee.divide(divisor, 0, RoundingMode.HALF_UP);
        BigDecimal feeVat = selFee == null ? null : selFee.subtract(fee);

        return new SettlementLineDraft(
                text(row, "ordNo"),
                optionKey,
                refund ? SaleType.REFUND : SaleType.SALE,
                date(row, "pocnfrmDt"),
                date(row, "ordStlEndDt"),
                date(row, "stlPlnDy"),
                null,
                quantity(row),
                positive(sum(row, "selPrc", "optAmt"), refund),
                fee,
                feeVat,
                ratio(row, divisor),
                positive(sum(row, "cupnAmt", "sellerCupnAmt", "tmallApplyDscAmt", "cardDscSellerDfrmAmt",
                        "sellerDfrmMultiDscCst", "sbdscSellerDfrmAmtSum"), refund),
                positive(sum(row, "dlvAmt", "bmStlDlvCst", "clmDlvCst", "addClmDlvCst", "clmFstDlvCst",
                        "bmClmFstDlvCst", "abrdCnDlvCst"), refund),
                positive(settlement, refund),
                ElevenstSettlementRow.keep(row));
    }

    /** {@code selFixedFee} ({@code "12.00%"}) → VAT-exclusive ratio; unreadable → null. */
    private static BigDecimal ratio(Map<String, String> row, BigDecimal divisor) {
        String value = text(row, "selFixedFee");
        if (value == null) {
            return null;
        }
        try {
            BigDecimal percent = new BigDecimal(value.replace("%", "").replace(",", "").trim());
            return percent.divide(BigDecimal.valueOf(100).multiply(divisor), 4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer quantity(Map<String, String> row) {
        BigDecimal value = money(row, "ordQty");
        return value == null ? null : value.intValue();
    }

    private static BigDecimal positive(BigDecimal value, boolean refund) {
        return (refund && value != null) ? value.abs() : value;
    }
}
