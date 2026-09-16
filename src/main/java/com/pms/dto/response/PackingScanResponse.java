package com.pms.dto.response;

import com.pms.domain.ParcelStatus;
import com.pms.service.packing.BoxCandidate;

import java.util.List;

/**
 * 송장 스캔 결과 — 박스 + 담을 것 + 상자 후보 (FEATURE_2609_40 / PLAN D9 ~ D13 · D27 · D31).
 *
 * <p>🔴 {@code remaining} 은 <b>주문 상태로 거르지 않는다</b>(D27). 포장은 <i>발송처리 → 라벨 → 포장</i>
 * 순서라 이 시점의 주문은 이미 배송지시다 — 출고 화면의 상태 필터를 가져오면 스캔한 송장이 전부
 * 빈 목록이 된다. 빠지는 것은 전량 취소 라인뿐이다.
 *
 * <p>🔴 {@code isLastParcel} = 같은 배송 묶음에서 <b>작업 대상인 박스가 이것 하나뿐인가</b>(D13 · D31).
 * 작업 대상 = {@code PENDING} 이면서 잔량 &gt; 0. 참이면 [이 박스 완료]는 남은 물품 <b>전량</b>을 요구한다 —
 * 마지막 박스가 "물건이 남은 채로 주문이 끝나는" 것을 막는 유일한 검문소다.
 *
 * @param boxCandidates 스캔 시점에는 <b>비어 있다</b>. 담긴 조합이 정해지면 화면이
 *                      {@code POST /packing/box-candidates} 로 다시 묻는다(D23)
 * @param unexpanded    전개 실패 라인. 하나라도 있으면 이 박스는 완료할 수 없다 — 무엇이 나가는지 모르는
 *                      채로 재고를 깎지 않는다
 */
public record PackingScanResponse(
        ParcelView parcel,
        OrderView order,
        List<PackingRemainingItem> remaining,
        List<BoxCandidate> boxCandidates,
        List<OutboundUnexpandedView> unexpanded,
        boolean isLastParcel) {

    /**
     * 스캔한 박스 자체.
     *
     * @param totalParcels 같은 배송 묶음의 박스 수(= 발급된 송장 장수). 박스 개수의 상한이다(D17)
     * @param status       {@code PACKED} 면 화면이 "이미 출고됨", {@code UNUSED} 면 "사용하지 않은 박스"를
     *                     띄운다 — 둘 다 예외가 아니라 정상 응답이다
     */
    public record ParcelView(Long id, String invoiceNumber, String carrierName, Integer parcelSeq,
                             int totalParcels, ParcelStatus status) {
    }

    /**
     * 화면 상단에 주문을 확인시켜 주는 최소 정보.
     *
     * <p>🔴 이름 두 개를 <b>그대로</b> 싣고 무엇을 보일지는 화면이 정한다(2609_54/D5) — 서버가 고르면
     * 화면 문구가 서버에 묶인다. 마스킹하지 않는다: 작업자가 실물 송장의 받는 사람과 대조하는 값이다.
     * 🔴 연락처·주소는 싣지 않는다 — 2026-07-11 PII 미저장 결정에서 <b>이름만</b> 완화됐다(2609_06).
     */
    public record OrderView(String externalOrderId, String sellerName,
                            String ordererName, String receiverName) {
    }
}
