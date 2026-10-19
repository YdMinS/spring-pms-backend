package com.pms.dto.request;

/**
 * The person's first-month answer on an 11st server-fee link (FEATURE_2610_20 / D45 ⑦).
 *
 * @param charged [예] = true (M0 is charged) · [아니오] = false. Required: the service answers 400
 *                「예 또는 아니오를 골라 주세요.」 when it is missing.
 */
public record FirstMonthAnswerRequest(Boolean charged) {
}
