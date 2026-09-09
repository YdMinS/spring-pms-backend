package com.pms.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 정산 매출내역 적재 결과 (FEATURE_2609_30 / PLAN D11).
 *
 * @param accounts        시도한 계정 수(가드로 건너뛴 계정 포함)
 * @param lines           upsert 된 라인 수
 * @param matched         {@code order_line} 매칭 성공 수
 * @param unmatched       매칭 실패 — <b>정상 상태</b>다(D7). 광고비 상계·기간 밖 주문은 매칭될 수 없다
 * @param duplicates      같은 실행 내 유일키 중복. 🔴 0 이어야 정상이며, 0 이 아니면 upsert 가 두 라인을
 *                        하나로 덮어써 금액이 사라졌다는 뜻이다(01 「구현 후 dev 검증」 ①)
 * @param skipped         수동 최소 간격 가드에 걸려 마켓을 호출하지 않음(대상 계정이 전부 걸렸을 때만 true)
 * @param nextAvailableAt {@code skipped} 일 때만 non-null — 가장 이른 재시도 가능 시각
 * @param failedAccounts  계정별 실패 요약. 한 계정의 실패가 나머지를 중단시키지 않는다
 */
public record SettlementSyncResponse(
        int accounts,
        int lines,
        int matched,
        int unmatched,
        int duplicates,
        boolean skipped,
        LocalDateTime nextAvailableAt,
        List<String> failedAccounts) {
}
