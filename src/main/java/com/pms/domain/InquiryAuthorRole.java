package com.pms.domain;

/**
 * 고객문의 답변의 작성 주체 (FEATURE_2609_23 / PLAN §3).
 *
 * 상세의 좌측 스레드가 정렬·배경색을 가르는 근거다(PLAN §6). 상품문의 답변은 전부 판매자이고,
 * 고객센터는 상담사({@code csAgent})와 판매자({@code vendor})가 섞인다.
 *
 * <p>⚠️ 고객(질문자)은 여기에 없다 — 문의 본문은 헤더 컬럼이지 답변 행이 아니다(D6).
 */
public enum InquiryAuthorRole {
    SELLER,
    CS_AGENT
}
