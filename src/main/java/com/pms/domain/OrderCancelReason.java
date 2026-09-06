package com.pms.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 발송 전 주문 취소 사유 (FEATURE_2609_25 / PLAN D5 · D18).
 *
 * <p>화면 라벨 → 쿠팡 {@code middleCancelCode} 매핑의 <b>유일한 소유자</b>다. 클라이언트에 코드→라벨
 * 상수를 두지 않는다 — 사유 목록은 {@code GET /api/admin/orders/cancel-reasons} 가 내려주고 서버 검증도
 * 같은 목록(이 enum 의 역직렬화)을 쓴다(D4).
 *
 * <p>⚠️ 쿠팡 {@code middleCancelCode} 는 <b>문서상 3종뿐</b>이다({@code CCTTER}·{@code CCPRER}·
 * {@code CCPNER}). WING 화면의 상세사유 10종(상품오노출·배송지연·파손 등)은 VOC 조회 응답용
 * {@code reasonCode} 표의 값이지 이 API 의 요청값이 아니다 — 그래서 여기에 오지 않는다.
 * {@code bigCancelCode} 는 {@code CANERR} 고정이라 enum 이 아니라 서비스 상수로 둔다.
 *
 * <p>⚠️ <b>INSTRUCT(상품준비중) 건은 쿠팡이 사유를 "배송불만/품절"로 덮어쓴다</b>(상세사유는
 * "파트너 API 강제취소"로 기록). 즉 우리가 고른 사유가 살아남는 곳은 ACCEPT 건과 우리
 * {@code order_cancel_action} 뿐이다.
 *
 * <p>확장(dev 실계정에서 VOC 코드 수용이 확인되는 경우)은 <b>이 enum 한 곳</b>만 고친다 —
 * 스키마·API·화면 변경 0(D18).
 */
@Getter
@RequiredArgsConstructor
public enum OrderCancelReason {

    /** 재고 연동 오류 = 품절. */
    OUT_OF_STOCK("상품 품절 / 재고 부족", "CCTTER"),

    /** 가격등재 오류. */
    WRONG_PRICE("잘못된 가격 기재", "CCPRER"),

    /** 가격등재 오류와 같은 코드로 보낸다 — 쿠팡에 상품정보 전용 코드가 없다. */
    WRONG_PRODUCT_INFO("상품 정보 오류 (오노출·상품명·상품정보)", "CCPRER"),

    /** 제휴사이트 오류 = 주소 문제로 배송지 생성 실패. */
    ADDRESS_ISSUE("배송지(주소) 문제로 발송 불가", "CCPNER");

    /** 화면에 보이는 한글 문구. */
    private final String label;

    /** 쿠팡에 전송하는 값. */
    private final String middleCancelCode;
}
