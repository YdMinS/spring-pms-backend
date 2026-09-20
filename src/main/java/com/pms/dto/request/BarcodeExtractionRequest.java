package com.pms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 물품 사진에서 바코드를 추출해 달라는 요청 (FEATURE_2609_65 / PLAN D1 · D7 · D10).
 *
 * <p>목록 일괄과 상세 단건이 <b>같은 창구</b>를 쓴다 — 상세 화면도 id 1개짜리 배열을 보낸다.
 * 단건용 엔드포인트를 따로 두면 중복 판정·덮어쓰기 규칙이 두 곳으로 갈라진다.</p>
 *
 * @param productIds 대상 물품 id (1~50개). 50은 서버 가드 전용 — 목록 한 페이지가 20개라 화면에서는 도달하지 않는다
 * @param overwrite  이미 바코드가 있는 물품도 덮어쓸지. 목록 일괄은 항상 보내지 않는다(= 덮어쓰지 않는다)
 */
public record BarcodeExtractionRequest(
        @NotEmpty(message = "물품을 선택해야 합니다")
        @Size(max = 50, message = "한 번에 최대 50개까지 추출할 수 있습니다")
        List<@NotNull Long> productIds,
        Boolean overwrite) {

    /** null = 보내지 않음 = 덮어쓰지 않음 (PLAN/D7). */
    public boolean overwriteOrFalse() {
        return Boolean.TRUE.equals(overwrite);
    }
}
