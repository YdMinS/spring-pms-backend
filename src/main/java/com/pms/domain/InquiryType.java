package com.pms.domain;

/**
 * 고객문의 종류 (FEATURE_2609_23 / PLAN D3).
 *
 * 쿠팡은 성격이 다른 문의 2종을 <b>서로 다른 API</b> 로 주고 두 응답은 한 필드도 겹치지 않는다.
 * 그래도 사용자가 하는 일은 같아서(미답변을 보고 → 관련 주문을 확인하고 → 답한다) 저장·화면은
 * 하나로 합치고 파서만 나눈다(D1·D3) — 이 값이 그 구분자다.
 *
 * <p>⚠️ 멱등 키에 <b>반드시</b> 들어간다(PLAN §3). 상품문의 {@code inquiryId} 와 고객센터
 * {@code inquiryId} 는 다른 시퀀스라 같은 숫자가 나올 수 있다({@link ClaimType} 이 키에 들어간 것과
 * 같은 사고를 미리 막는다).
 */
public enum InquiryType {

    /** 상품 페이지 Q&A — 구매 전 질문이 다수라 주문이 없는 것이 정상이다(D14·D15). */
    PRODUCT_QNA,

    /** 쿠팡 상담사가 판매자에게 이관한 건. */
    CALL_CENTER
}
