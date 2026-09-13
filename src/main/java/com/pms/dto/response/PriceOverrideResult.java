package com.pms.dto.response;

import java.util.List;

/**
 * 판매가 직접 입력 결과 (FEATURE_2609_42 / PLAN D1·D2·D4·D10).
 *
 * <p>🔴 <b>마켓 호출 0회다.</b> 그래서 {@link RepricePushResult} 에 있는 {@code stopped}·{@code retryAfter} 가
 * 여기에는 없다 — 쿨다운이 존재하지 않는다. 입력값을 실판매가로 만들려면 사람이 [마켓 반영]을 따로 누른다(D1).</p>
 *
 * <p>🔴 저장된 옵션은 {@code price_source} 가 <b>그대로</b>다(D2) — AUTO 는 AUTO 로 남아 다음 재계산이
 * 공식값으로 덮고(「이번 한 번만」의 정의), 직접 지정가는 직접 지정가로 남는다(2609_43 D2).
 * {@code market_price} 는 건드리지 않으므로 그 행은 곧바로 「아직 안 밀림」으로 뜬다(D10).</p>
 *
 * @param applied 판매가를 저장한 옵션 수
 * @param skipped 저장하지 않은 옵션 — 판매중 아님. 🔴 화면이 걸렀다고 믿지 않고 서버가 다시 판정한 결과다.
 *                ⚠️ 「마켓 식별자 없음」은 여기 들어오지 <b>않는다</b>: 전송에만 필요한 조건이라 아직 등록 전인
 *                셀도 가격은 정할 수 있다. ⚠️ 「직접 지정한 가격」도 더는 들어오지 않는다(2609_43 D1)
 * @param failed  저장이 실패한 옵션. 옵션마다 독립 커밋이라 나머지는 그대로 저장돼 있다
 */
public record PriceOverrideResult(int applied, List<SkippedOption> skipped, List<FailedOption> failed) {

    /** 입력 대상이 아니어서 건너뛴 옵션 1건. {@code reason} 은 서버가 정한 문장이고 화면은 그대로 보여준다. */
    public record SkippedOption(Long optionId, String optionName, String reason) {
    }

    /** 저장이 실패한 옵션 1건. */
    public record FailedOption(Long optionId, String optionName, String message) {
    }
}
