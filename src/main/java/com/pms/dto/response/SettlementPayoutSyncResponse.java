package com.pms.dto.response;

import java.util.List;

/**
 * 지급내역 적재 결과 (FEATURE_2609_30 / 02 · PLAN D5-2·D5-5).
 *
 * @param payouts         upsert 한 지급 묶음 수. 🔴 같은 인식월에 여러 건이 정상이다(D5-3)
 * @param attributedLines 이번에 주인이 생긴 라인 수. <b>0 도 정상</b>이다 — ADDITIONAL/RESERVE 는 라인을
 *                        가져가지 않고(D5-5), 매출내역 적재가 아직 안 됐을 수도 있다
 * @param adjustments     upsert 한 조정 행 수 (차감·전주채무·보류해제…)
 * @param failedAccounts  계정 단위로 격리된 실패. 한 계정의 실패가 나머지를 멈추지 않는다
 */
public record SettlementPayoutSyncResponse(int accounts, int payouts, int attributedLines,
                                           int adjustments, List<String> failedAccounts) {
}
