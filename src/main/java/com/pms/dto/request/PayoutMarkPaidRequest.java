package com.pms.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * [지급 완료] on an 11st payout (FEATURE_2610_20 / D34 · D38).
 *
 * @param paidOn the date the money actually arrived — the screen fills the remittance date first, or today (KST)
 *               when that is still ahead. Required: the service answers 400 「실제 입금일을 골라 주세요.」 when it is
 *               missing and 「실제 입금일은 오늘 이후로 정할 수 없습니다.」 when it is after today (KST, D38).
 */
public record PayoutMarkPaidRequest(@JsonFormat(pattern = "yyyy-MM-dd") LocalDate paidOn) {
}
