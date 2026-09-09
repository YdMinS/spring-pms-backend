package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 지급 묶음 1건의 요약 (FEATURE_2609_30 / 02 · PLAN D3 · D5-3).
 *
 * <p>목록·상세·리포트가 같은 요약을 쓴다 — 화면마다 다른 필드를 내려주면 "왜 목록과 상세 금액이 다르지"가 난다.
 *
 * <p>⚠️ 소유 축은 <b>채널(marketplaceAccount)</b> 이다. 판매자는 표기용이며 정산은 vendorId 단위로 온다(D3).
 * ⚠️ {@code lineCount == 0} 은 정상이다(D5-4) — 유보금 해제·채무 상환·광고비 정산은 판매 라인이 없다.
 */
public record PayoutSummary(
        Long payoutId,
        Long accountId,
        String platform,
        String accountAlias,
        Long sellerId,
        String sellerName,
        String settlementType,
        String revenueRecognitionMonth,
        LocalDate recognitionFrom,
        LocalDate recognitionTo,
        LocalDate settlementDate,
        LocalDate finalSettlementDate,
        BigDecimal totalSale,
        BigDecimal serviceFee,
        BigDecimal finalAmount,
        String status,
        String reconStatus,
        long lineCount) {
}
