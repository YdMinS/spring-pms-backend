package com.pms.dto.response;

import java.time.LocalDateTime;

/**
 * 작업 대상 박스 1건 (FEATURE_2609_40 / PLAN D16 · D31).
 *
 * <p>🔴 이 목록에는 {@code PENDING} 이면서 <b>잔량 &gt; 0</b> 인 박스만 든다(D31). 동기화 백필은 송장이
 * 붙은 <b>모든 과거 배송 묶음</b>에 {@code PENDING} 박스를 만들기 때문에, 상태만 보고 뽑으면 이미 출고가
 * 끝난 과거 주문이 첫날부터 목록을 가득 채운다.
 *
 * @param remainingQty 아직 안 나간 수량 합계 — 이 값이 0 이면 애초에 목록에 없다
 */
public record PendingParcelView(Long parcelId, String invoiceNumber, String carrierName,
                                Integer parcelSeq, int totalParcels, String externalOrderId,
                                String sellerName, int remainingQty, LocalDateTime orderedAt) {
}
