package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 판매가 직접 입력 요청 (FEATURE_2609_42 / PLAN D3·D6·D7·D8).
 *
 * <p>🔴 단위는 <b>옵션 1건</b>이다(D3) — 옵션마다 원가가 달라 한 값을 상품 전체에 밀면 옵션별 마진이 제각각이
 * 된다. 상한 200 은 {@link RepricePushRequest} 와 같은 축·같은 숫자다(D7): 상한이 둘로 갈리면 화면이 두 벌의
 * 분할 규칙을 갖는다.</p>
 *
 * <p>🔴 이 요청은 <b>로컬 판매가만</b> 바꾼다(D1). 마켓 전송은 {@code push} 가 따로 한다 — 가격을 전송 요청에
 * 실으면 「로컬에 기록되지 않은 값이 마켓으로 직행하는 경로」가 생겨 무엇이 걸렸는지 DB 로 되짚을 수 없다(D8).</p>
 *
 * <p>⚠️ <b>원가 미만을 막지 않는다</b>(D6). 재고 소진·미끼처럼 손해 보는 가격을 의도적으로 거는 경우가 있고,
 * 판단은 사람 몫이다(화면이 마진을 미리 보여 경고한다). 서버 검증은 0 초과와 상한뿐이다.</p>
 */
public record PriceOverrideRequest(
        @NotEmpty(message = "가격을 입력할 옵션을 선택하세요")
        @Size(max = PriceOverrideRequest.MAX_OPTIONS,
                message = "한 번에 최대 " + PriceOverrideRequest.MAX_OPTIONS + "개 옵션까지 입력할 수 있습니다 — 나눠 실행하세요")
        @Valid List<Item> items) {

    /** 한 요청의 옵션 상한(D7). {@code push} 와 같은 숫자다. */
    public static final int MAX_OPTIONS = 200;

    /**
     * 옵션 1건의 새 판매가.
     *
     * <p>🔴 {@code @Digits} 는 {@code product_listing_option.selling_price}(precision 10, scale 2)에 맞춘다 —
     * 칼럼이 못 담는 값을 400 으로 세우지 않으면 저장 시점에 정체불명의 실패가 된다.</p>
     */
    public record Item(
            @NotNull(message = "옵션을 지정하세요") Long optionId,
            @NotNull(message = "판매가를 입력하세요")
            @DecimalMin(value = "0", inclusive = false, message = "판매가는 0보다 커야 합니다")
            @Digits(integer = 8, fraction = 2, message = "판매가 형식이 올바르지 않습니다") BigDecimal price) {
    }
}
