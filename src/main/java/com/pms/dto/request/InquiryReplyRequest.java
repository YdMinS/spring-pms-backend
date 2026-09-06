package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 고객문의 답변 전송 요청 — POST /api/admin/inquiries/{id}/replies (FEATURE_2609_23 / 04).
 *
 * <p>⚠️ {@code @Size} 를 붙이지 않는다 — 길이 제약은 <b>유형마다 다르고</b>(상품문의 1자 / 고객센터 2자)
 * 판정의 소유자는 {@code InquiryReplyPolicy} 하나다(D5). DTO 에 숫자를 박으면 화면이 받는
 * {@code replyCapability.minLength} 와 서버 검증이 갈린다.
 * <p>⚠️ {@code parentReplyId} 를 받지 않는다 — 서버가 {@code transfer_status} 로 고른 값만 쓴다.
 */
public record InquiryReplyRequest(
        @Schema(description = "Reply body sent to the customer", example = "내일 출고 예정입니다.")
        @NotBlank(message = "답변 내용을 입력해 주세요.")
        String content) {
}
