package com.pms.dto.response;

import com.pms.domain.InquiryAuthorRole;

import java.time.LocalDateTime;

/**
 * 고객문의 답변 1건 — GET /api/inquiries/{id} 의 스레드 요소 (FEATURE_2609_23).
 *
 * {@code authorRole} 이 좌측 스레드의 정렬·배경색을 가른다(PLAN §6).
 * {@code transferStatus} 는 이관 상태 <b>원문</b>이다(고객센터 전용) — 화면이 이걸 보여주면
 * 사용자가 왜 아직 자기 차례인지를 스스로 안다.
 */
public record CustomerInquiryReplyResponse(
        Long id,
        String externalReplyId,
        String parentExternalReplyId,
        InquiryAuthorRole authorRole,
        String authorName,
        String content,
        String transferStatus,
        LocalDateTime repliedAt) {
}
