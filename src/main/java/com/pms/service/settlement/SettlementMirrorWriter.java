package com.pms.service.settlement;

import com.fasterxml.jackson.databind.JsonNode;
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
     * 저장된 중립 라인에 대응하는 미러 행을 멱등하게 남긴다(있으면 갱신).
     *
     * @param line 이미 저장된 중립 라인 (id 확정)
     * @param raw  응답 원문 라인 1개
     */
    void write(SettlementLine line, JsonNode raw);
}
