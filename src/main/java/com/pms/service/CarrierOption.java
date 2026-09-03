package com.pms.service;

/**
 * 단건 발송처리 택배사 드롭다운 항목.
 *
 * <p>식별자는 <b>마켓 코드 자체</b>다 — 쿠팡은 택배사 목록 API 가 없고 문서의 정적 코드표가
 * SSOT 이라, 로컬 {@code carrier} 행이 없는 택배사(대부분)에는 붙일 id 가 없다
 * (PLAN 2609_11 D2 개정 2026-09-03). 서버는 받은 코드를 {@link CoupangCourierCodes} 화이트리스트로
 * 검증하고 전송하므로 임의 문자열이 쿠팡까지 가지 않는다.
 *
 * @param deliveryCompanyCode 그 플랫폼에서의 택배사 코드(예: 쿠팡 "CJGLS") — 전송 시 그대로 되돌아온다
 * @param carrierName         표시용 이름(예: "CJ대한통운")
 * @param registered          택배사 관리(로컬 {@code platform_carrier_code})에 등록된 코드인가 — 목록 상단 고정용
 */
public record CarrierOption(String deliveryCompanyCode, String carrierName, boolean registered) {
}
