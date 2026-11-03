package com.pms.service.sales;

import com.pms.dto.response.ChannelSalesResponse;
import com.pms.dto.response.DailySalesResponse;
import com.pms.dto.response.ProductProfitResponse;
import com.pms.dto.response.SalesLineView;
import com.pms.dto.response.SellerSalesResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * Sales aggregation — answers <b>the sales up to today</b> (FEATURE_2609_30 / PLAN D2 · D4 · D14).
 *
 * <p>🔴 <b>Sales are never built by reading the settlement ledger.</b> The source of truth for sales is
 * {@code order_line} (D2). Both settlement APIs run on the revenue-recognition date (purchase confirmed or delivered
 * + 3 days), so they cannot give today's numbers at all, and mixing the two axes always ends in
 * <i>"why did yesterday's sales change"</i>. The estimated settlement is built from order lines too
 * (FEATURE_2611_05 D1); the only things taken from the ledger are a channel's payouts paid in the period
 * ({@code paidAmount}) and the 11st purchase-confirmed amount used to judge the fixed cost (FEATURE_2610_20 / D42).
 *
 * <p>The three methods use <b>one and the same aggregation path</b> ({@code OrderLineRepository.aggregateSales}) and
 * only change the group key — so the seller totals and the per-product list totals never drift apart.
 *
 * <p>Period defaults: {@code to} = today, {@code from} = the 1st of this month. {@code from > to} is 400.
 */
public interface SalesStatsService {

    // excludeUnshipped on ①②③④ (FEATURE_2611_04 D1 · D2 · D6): true = every number built from order lines
    // (sales, discount, fee, quantity, refund columns, net profit, estimated settlement — FEATURE_2611_05 D6) and the
    // ④ line list leave out the lines whose status is still PAID or PREPARING; a line with no status stays in.
    // 🔴 The fixed cost is still subtracted in full and judged on the whole month's sales of every line. false = the
    // numbers as before (the dashboard cards and the stock rank pass false).

    /** ① 판매자별 한 줄. {@code sellerId} 가 null 이면 전 판매자. */
    List<SellerSalesResponse> summary(LocalDate from, LocalDate to, Long sellerId, boolean excludeUnshipped);

    /** ② 채널(계정)별 한 줄. 현금주의({@code paidAmount})는 여기서만 제공한다(D4-1). */
    List<ChannelSalesResponse> byChannel(LocalDate from, LocalDate to, Long sellerId, boolean excludeUnshipped);

    /**
     * ③ 상품별 수익성.
     *
     * @param crossChannel true = 마스터 상품 단위로 합친다 / false = 마스터 × 채널로 쪼갠다
     */
    List<ProductProfitResponse> byProduct(LocalDate from, LocalDate to, Long sellerId, boolean crossChannel,
                                          boolean excludeUnshipped);

    /**
     * ④ 판매 내역 — 한 채널의 주문 라인 목록 (FEATURE_2609_34).
     *
     * <p>①②③ 이 접어서 합계를 내는 반면 이쪽은 접지 않는다. 채널별 매출 화면이 "이 매출이 어느 주문에서
     * 나왔나"에 답하는 자리이고, 마켓 관리자 화면과 대조할 수 있는 유일한 축이다.
     *
     * <p>⚠️ {@code accountId} 는 필수다 — 한 채널을 들여다보는 목록이라 전 채널을 한 번에 내려보내지 않는다.
     */
    List<SalesLineView> lines(LocalDate from, LocalDate to, Long accountId, boolean excludeUnshipped);

    /**
     * ⑤ Sales per sale date — the dashboard sales chart (FEATURE_2610_29 D22). One row per date from {@code from}
     * to {@code to} (ascending), a date without sales included with zeros. Same period defaults and 400 as above.
     * {@code sellerId} null = no seller filter (FEATURE_2610_30 D34).
     */
    List<DailySalesResponse> daily(LocalDate from, LocalDate to, Long sellerId);
}