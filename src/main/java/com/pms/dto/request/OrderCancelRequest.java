package com.pms.dto.request;

import com.pms.domain.OrderCancelReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 발송 전 주문 취소 요청 (FEATURE_2609_25 / PLAN D1 · D4).
 *
 * <p>취소 단위는 <b>라인(옵션) × 수량</b>이다 — 발주처리(박스 단위, 수량 없음)와 다르다.
 * 화면은 주문 상세에서 1건만 채우지만 계약은 배열로 둔다(서버가 계정→orderId→boxId 로 그룹핑해
 * 그룹마다 1 POST 를 보낸다).
 *
 * <p>사유는 enum 역직렬화가 곧 검증이다 — 목록 밖의 값은 400 이고, 목록의 원천은
 * {@code GET /api/admin/orders/cancel-reasons}(같은 enum) 하나뿐이다.
 *
 * <p>record 사용 → accessor 자동 생성 (Lombok 금지).
 */
public record OrderCancelRequest(

        @NotEmpty(message = "취소할 주문 라인을 1건 이상 선택하세요")
        @Size(max = 50, message = "한 번에 50건까지 취소할 수 있습니다")
        @Valid
        List<Line> lines,

        @NotNull(message = "취소 사유를 선택하세요")
        OrderCancelReason reason) {

    /**
     * 취소할 라인 1건. {@code quantity} 상한은 서버가 {@code purchasableQty()} 로 다시 본다(D3) —
     * 되돌릴 수 없는 API 라 쿠팡에 맡기지 않고 우리가 먼저 400 으로 막는다.
     */
    public record Line(
            @NotNull(message = "주문 라인 id가 필요합니다") Long orderItemId,
            @NotNull(message = "취소 수량이 필요합니다") @Min(value = 1, message = "취소 수량은 1개 이상이어야 합니다")
            Integer quantity) {
    }
}
