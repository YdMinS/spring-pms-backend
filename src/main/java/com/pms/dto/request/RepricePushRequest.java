package com.pms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 마켓 반영(가격 전송) 요청 (FEATURE_2609_39 / PLAN D7 ② · D11 · D21).
 *
 * <p>🔴 단위는 <b>옵션</b>이다(재계산과 축이 다르다 — D11). 전송은 쿠팡 vendorItem 1건 = PUT 1회라
 * 옵션 목록을 그대로 받는다.</p>
 *
 * <p>🔴 이 요청은 <b>실제 판매 가격을 바꾼다.</b> 쿠팡 가격변경은 승인 없이 즉시 실판매가다 — 그래서 재계산과
 * 끝까지 분리된 엔드포인트다(D7). 🔴 상한 200 의 실제 소요는 미측정이다(D26): 200 = 쿠팡 PUT 200회 순차라
 * 게이트웨이 타임아웃 구간이고, 화면은 실측 전까지 50건씩 나눠 보낸다.</p>
 */
public record RepricePushRequest(
        @NotEmpty(message = "반영할 옵션을 선택하세요")
        @Size(max = RepricePushRequest.MAX_OPTIONS,
                message = "한 번에 최대 " + RepricePushRequest.MAX_OPTIONS + "개 옵션까지 반영할 수 있습니다 — 나눠 실행하세요")
        List<Long> optionIds) {

    /** 한 요청의 옵션 상한(D11). */
    public static final int MAX_OPTIONS = 200;
}
