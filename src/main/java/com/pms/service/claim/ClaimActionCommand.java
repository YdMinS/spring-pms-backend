package com.pms.service.claim;

import com.pms.domain.ClaimAction;

/**
 * 어댑터에 전달되는 실행 요청 (FEATURE_2609_21 / PLAN D14).
 *
 * <p>요청 DTO 를 그대로 넘기지 않는 이유 = 어댑터가 웹 계층 타입을 모르게 하기 위해서다.
 *
 * @param deliveryCompanyCode 택배사 코드. {@code CarrierCodeService} 화이트리스트를 이미 통과한 값
 * @param regNumber           회수 송장의 선택 항목(등기번호)
 * @param rejectCode          거부 사유 코드(05 에서 사용)
 */
public record ClaimActionCommand(ClaimAction action,
                                 String deliveryCompanyCode,
                                 String invoiceNumber,
                                 String regNumber,
                                 String rejectCode) {
}
