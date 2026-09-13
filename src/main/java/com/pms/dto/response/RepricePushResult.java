package com.pms.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * 마켓 반영(가격 전송) 결과 (FEATURE_2609_39 / PLAN D7 ② · D22 · D26).
 *
 * <p>🔴 <b>부분 실패가 정상 경로다.</b> 옵션마다 전송 + 저장이 독립 트랜잭션이라, 실패한 옵션은
 * {@code market_price} 를 갱신하지 않고 그대로 남는다 — 다음 조회에서 「아직 안 밀림」으로 다시 뜬다(D15).
 * 마켓은 롤백되지 않으므로 이미 나간 건을 되돌리는 일은 하지 않는다.</p>
 *
 * @param pushed     마켓이 받아들인 옵션 수(= {@code market_price} 를 갱신한 수)
 * @param skipped    전송하지 않은 옵션 — 미지원 채널 · 판매중 아님(D20) · 마켓 식별자 없음. 🔴 화면이 걸렀다고
 *                   믿지 않고 서버가 다시 판정한 결과다. ⚠️ 직접 지정가는 더는 여기 들어오지 않는다(2609_43 D1)
 * @param failed     마켓이 거부했거나 계정이 없는/비활성인 옵션(D22). <b>저장하지 않았다</b>
 * @param stopped    429 쿨다운으로 남은 옵션을 중단했는지. true 면 목록 뒤쪽은 전송도 판정도 되지 않았다
 * @param retryAfter {@code stopped} 일 때의 재시도 가능 시각(쿠팡 쿨다운 종료, 약 10분). 화면은 「잠시 후」가
 *                   아니라 이 시각을 보여준다(D26). 그 외에는 null
 */
public record RepricePushResult(int pushed, List<SkippedOption> skipped, List<FailedOption> failed,
                                boolean stopped, Instant retryAfter) {

    /** 전송 대상이 아니어서 건너뛴 옵션 1건. {@code reason} 은 서버가 정한 문장이고 화면은 그대로 보여준다. */
    public record SkippedOption(Long optionId, String optionName, String reason) {
    }

    /** 전송이 실패한 옵션 1건. {@code message} 는 플랫폼(또는 계정 해석)의 메시지 그대로다. */
    public record FailedOption(Long optionId, String optionName, String message) {
    }
}
