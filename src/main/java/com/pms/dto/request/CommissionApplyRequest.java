package com.pms.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 수수료율 확정 반영 요청 (FEATURE_2609_30 / 06 · PLAN D16).
 *
 * <p>🔴 <b>요청에 실린 항목만</b> 반영한다. "전부 적용"을 서버가 알아서 하지 않는다 — 수수료율은 앞으로의
 * 판매가로 파급되므로 사람이 고른 것만 움직여야 한다.
 *
 * <p>⚠️ {@link #sellerId}/{@link #from}/{@link #to} 는 <b>사용자가 보고 있던 창</b>이다. 서버는 이 창으로
 * 실측을 다시 집계해 요청값과 대조하고(낙관적 검증), 그 사이에 데이터가 바뀌었으면 409 로 거절한다.
 * 비우면 제안 목록과 같은 기본 구간을 쓴다.
 */
public record CommissionApplyRequest(
        Long sellerId,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate from,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate to,
        @NotEmpty(message = "반영할 항목이 없습니다") @Valid List<Item> items
) {

    /**
     * @param newRate 저장할 수수료율 — <b>부가세 별도</b>, {@code 0 <= rate < 1}. 실측(부가세 포함)을
     *                그대로 넣으면 부가세만큼 과대 반영되므로 화면은 {@code suggestedRate} 를 보낸다.
     */
    public record Item(
            @NotNull(message = "카테고리 id 가 없습니다") Long platformCategoryId,
            @NotNull(message = "수수료율이 없습니다") BigDecimal newRate
    ) {
    }
}
