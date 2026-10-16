package com.pms.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order line response DTO (GET /api/orders).
 *
 * <p>Folds the three order layers (orders → order_shipment → order_line) and the platform mirror
 * ({@code coupang_order_line} · {@code elevenst_order_line}, FEATURE_2610_17 / D5) into one screen row. raw (the
 * original payload) and sensitive data are left out to keep the list light, and the derived purchasableQty is
 * computed ahead. Only customer <b>names</b> (orderer · receiver) are included (FEATURE_2609_06 / D5) — contacts and
 * addresses are never stored in the first place.
 *
 * <p>🔴 {@code status} is the <b>neutral status</b> ({@code OrderStatus} name) — the marketplace's original goes
 * separately as {@code platformStatus} (FEATURE_2609_26 / PLAN D4). Clients build labels from the neutral value.
 * <p>🔴 {@code effectiveStatus} is <b>gone</b> — {@code status} expresses a full cancel as {@code CANCELLED}
 * (PLAN D26, the server owns the decision). {@code cancelled} stays for badges and filters.
 *
 * <p>⚠️ The name stays {@code OrderItemResponse} — renaming the DTO would shake the types of both clients, which
 * is not this piece's purpose.
 * <p>⚠️ No shipping fee — it is per box (order_shipment); copying it to lines would double the totals.
 */
@Getter
@AllArgsConstructor
@Builder
public class OrderItemResponse {
    private Long id;
    private Long marketplaceAccountId;
    private String platform;
    private String externalOrderId;
    private String externalBoxId;       // = order_shipment.external_shipment_id (박스 없으면 null)
    private String externalItemId;      // = coupang_order_line.vendor_item_id · elevenst_order_line.prd_stck_no (option mapping check)
    private String itemName;
    private String ordererName;         // 주문자 이름 (없으면 null)
    private String receiverName;        // 수취인 이름 (없으면 null)
    private int orderCount;
    private int cancelCount;
    private int holdCount;
    private int purchasableQty;         // orderCount-(cancel+hold), 음수 0
    private String status;              // 중립 상태 (PAID·PREPARING·SHIPPED·DELIVERING·DELIVERED·CANCELLED)
    private String platformStatus;      // 플랫폼 원문 (쿠팡 ACCEPT 등) — 거울 행이 없으면 null
    private boolean cancelled;          // 전량 취소 여부 (취소했는데 상품준비중 오표시 방지)
    // 내부 단계 (FEATURE_2609_75 / D9) — INTERNAL_PREPARING · AWAITING_SHIPMENT · null. status 와 별개다.
    private String internalStage;
    private LocalDateTime paidAt;       // = orders.ordered_at
    // 주문 시점 금액 스냅샷 (PLAN D9·D10). 과거분은 백필률만큼만 채워지므로 nullable 이다.
    private BigDecimal unitPrice;
    private BigDecimal lineAmount;              // 할인 전 라인 합계
    private BigDecimal discountAmount;          // 총 할인
    private BigDecimal platformDiscountAmount;  // 플랫폼 부담 할인
    // raw(JSON 원본)는 응답에서 제외 — 목록 가벼움 유지
    private boolean visitPickup;        // 11st visit-pickup order (raw visitDlvYn = Y) — FEATURE_2610_17 / D66
    private boolean reservedSale;       // 11st reserved-sale paid order (raw code 201) — FEATURE_2610_17 / D67
    private boolean stockConfirmSent;   // 11st reserved-sale [입고완료 처리] already sent — FEATURE_2610_17 / D67
}
