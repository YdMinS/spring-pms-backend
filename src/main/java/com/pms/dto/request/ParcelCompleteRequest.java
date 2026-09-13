package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * [이 박스 완료] — 한 박스에 무엇을 담았고 어떤 상자를 썼는지 (FEATURE_2609_40 / PLAN D14 · D19).
 *
 * <p>🔴 담은 내용은 <b>완료 때 한 번에</b> 온다. 스캔마다 저장하지 않는다(D14) — 스캔은 초당 여러 번
 * 일어나고, 브라우저가 꺼져도 다시 스캔할 분량은 박스 하나뿐이다.
 *
 * <p>🔴 이 요청 하나가 <b>사람의 확인</b>이다(D19): 서버는 이것을 받고서야 {@code STOCK_OUT} 을 남긴다.
 * 스캔 조회는 아무것도 만들지 않는다.
 *
 * <p>⚠️ 같은 요청이 두 번 들어와도 출고는 한 번뿐이다 — 박스가 이미 {@code PACKED} 면 현재 상태만
 * 돌려준다(D15). 스캐너 Enter 가 두 번 들어가는 일은 실제로 일어난다.
 */
public record ParcelCompleteRequest(
        @NotNull Long boxPackageId,
        @NotEmpty @Valid List<PackedItem> items,
        /** 출고 기록일. 생략하면 오늘. */
        LocalDate movedOn) {

    /**
     * 박스에 담은 것 1건.
     *
     * <p>🔴 {@code orderLineId} 가 필수인 이유는 합포장 대비다(D2) — 물품만 적으면 여러 주문을 한 박스에
     * 담게 될 때 어느 주문에서 나간 것인지 영영 알 수 없다.
     */
    public record PackedItem(
            @NotNull Long orderLineId,
            @NotNull Long productId,
            @NotNull @Min(1) Integer quantity) {
    }
}
