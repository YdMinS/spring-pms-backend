package com.pms.service.claim;

/**
 * 액션이 값 선택을 요구할 때의 선택지 1개 (FEATURE_2609_21 / PLAN D19).
 *
 * <p>선택지 집합은 <b>플랫폼 값</b>이다(교환 거부 사유 {@code SOLDOUT}/{@code WITHDRAW} 는 쿠팡 코드).
 * 프론트·모바일에 코드→라벨 상수를 두면 클라이언트 2벌이 쿠팡 지식을 갖게 되고, 네이버가 다른 코드
 * 집합을 쓰는 순간 화면을 함께 고쳐야 한다 — 그래서 어댑터가 내려준다. 서버 검증도 같은 목록을 쓰므로
 * 화면에 보이는 것과 서버가 받는 것이 구조적으로 일치한다.
 *
 * @param code  전송할 값
 * @param label 사용자에게 보일 한글 설명
 */
public record ActionChoice(String code, String label) {
}
