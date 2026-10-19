package com.pms.service.settlement;

import com.pms.domain.Platform;
import com.pms.domain.SettlementLine;

/**
 * 플랫폼 미러 행 기록 seam (FEATURE_2609_30 / PLAN D6).
 *
 * <p>{@link SettlementSource} 와 나눠 둔 이유: 조회(HTTP)와 미러 저장(DB)은 트랜잭션 경계가 다르고,
 * 미러를 중립 upserter 안에서 직접 만들면 중립 코드가 쿠팡 필드명({@code couranteeFee} 등)을 알게 된다.
 *
 * <p>⚠️ 구현이 없는 플랫폼은 미러 없이 중립 라인만 남는다 — 그것이 정상이다(네이버 어댑터는 비-스코프).
 */
public interface SettlementMirrorWriter {

    Platform platform();

    /**
     * Writes the mirror row of a stored neutral line, idempotently (updates it when it exists).
     *
     * @param line the neutral line, already stored (id assigned)
     * @param raw  one raw response line — the shape the platform's source put on the draft (Coupang = JsonNode ·
     *             11st = kept-field map, FEATURE_2610_20 / D16); a writer ignores any other shape
     */
    void write(SettlementLine line, Object raw);
}
