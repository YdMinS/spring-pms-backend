package com.pms.service;

/**
 * 단건 발송처리 택배사 드롭다운 항목.
 *
 * @param carrierId           선택 시 클라이언트가 되돌려 보내는 값(마켓 코드는 서버만 안다, PLAN 2609_11 D2)
 * @param carrierName         표시용 이름(예: "CJ대한통운")
 * @param deliveryCompanyCode 그 플랫폼에서의 코드(예: "CJGLS") — 표시 보조용
 */
public record CarrierOption(Long carrierId, String carrierName, String deliveryCompanyCode) {
}
