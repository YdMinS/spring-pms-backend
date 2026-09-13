package com.pms.dto.response;

import com.pms.domain.ParcelStatus;

/**
 * 「사용하지 않은 박스」로 닫은 결과 (FEATURE_2609_40 / PLAN D32).
 *
 * <p>🔴 출고·상자 기억·절약을 <b>하나도</b> 남기지 않는다 — 쓰지 않은 송장일 뿐 포장 사실이 아니다.
 * 되돌리기는 없다: 잘못 닫았으면 남은 물품은 다른 박스로 나간다(잔량은 그대로다).
 */
public record ParcelCloseResponse(Long parcelId, ParcelStatus status) {
}
