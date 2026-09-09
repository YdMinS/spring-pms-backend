package com.pms.service.stock;

import com.pms.domain.Product;
import com.pms.domain.StockLocation;

/**
 * 이행 경로 판정 — <b>함수 하나</b> (FEATURE_2609_28 / PLAN D17).
 *
 * <p>오늘 분기는 "자사 창고" 하나뿐이다. 3PL·공급처 직배송이 붙을 때 고칠 곳을 한 군데로 묶어 두는 것이
 * 이 클래스의 전부다 — 판정이 입고 서비스와 출고 서비스로 흩어지면 나중에 전부 찾아다녀야 하고,
 * 한쪽만 고치면 같은 창고의 행이 두 종류 location 을 달게 된다.
 *
 * <p>⚠️ 인스턴스를 만들지 않는다(상태 없음). 새 이동 유형을 추가할 때도 여기를 호출한다.
 */
public final class StockLocationPolicy {

    private StockLocationPolicy() {
    }

    /** 물품이 어디에서 움직였는가. 지금은 전부 자사 창고다. */
    public static StockLocation resolve(Product product) {
        return StockLocation.OWN;
    }
}
