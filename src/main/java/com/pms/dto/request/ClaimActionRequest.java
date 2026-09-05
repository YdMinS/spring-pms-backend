package com.pms.dto.request;

import com.pms.domain.ClaimAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 클레임 처리 액션 요청 — POST /api/admin/claims/{id}/actions (FEATURE_2609_21 / PLAN §5).
 *
 * <p>엔드포인트는 하나다. 액션마다 경로를 파면 7개가 되고 권한·감사·중복 가드가 7벌로 흩어진다.
 * {@code action} 은 서버가 {@code availableActions} 로 방금 내려준 값이라 클라이언트가 문자열을
 * 지어낼 여지가 없다.
 *
 * <p>액션별 필수 필드 검증은 서비스가 아니라 <b>이 DTO 와 액션 메타({@link ClaimAction.Requires})</b>가
 * 한다({@link #validateFor()}). 컨트롤러가 위임 전에 호출한다.
 *
 * <p>택배사는 <b>마켓 코드 자체</b>({@code deliveryCompanyCode})로 받는다 — 단건 발송처리(2609_11 D2
 * 개정 2026-09-03)와 같은 계약이다. 쿠팡은 택배사 목록 API 가 없고 문서의 정적 코드표가 SSOT 라
 * 로컬 {@code carrier} 행이 없는 택배사가 대부분이며, 임의 문자열은
 * {@link com.pms.service.CoupangCourierCodes} 화이트리스트가 거른다.
 *
 * <p>record 사용 → accessor 자동 생성 (Lombok 금지).
 */
public record ClaimActionRequest(
        @NotNull(message = "액션은 필수입니다") ClaimAction action,
        @Size(max = 30, message = "택배사 코드는 30자 이하여야 합니다") String deliveryCompanyCode,
        @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber,
        @Size(max = 50, message = "등기번호는 50자 이하여야 합니다") String regNumber,
        @Size(max = 50, message = "거부 사유 코드는 50자 이하여야 합니다") String rejectCode) {

    /**
     * 액션 메타가 요구하는 필드가 채워졌는지 검증한다. 여분 필드는 무시한다.
     *
     * @throws IllegalArgumentException 필수 필드 누락 (→400)
     */
    public void validateFor() {
        switch (action.getRequires()) {
            case INVOICE -> {
                requireText(deliveryCompanyCode, "택배사를 선택하세요");
                requireText(invoiceNumber, "송장번호는 필수입니다");
            }
            case REJECT_CODE -> requireText(rejectCode, "거부 사유는 필수입니다");
            case NONE -> {
                // 추가 입력 없음 — 여분 필드가 실려 와도 무시한다.
            }
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
