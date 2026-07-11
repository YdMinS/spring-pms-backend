package com.pms.service;

import java.util.List;

/**
 * 송장 접수용 스프레드시트 생성 서비스 (생성 레그).
 *
 * 쿠팡 ordersheets(status=INSTRUCT)를 <b>온디맨드 조회</b>해 택배사 접수용 xlsx 로 내려준다.
 *
 * <p>❌ 고객 개인정보(수령인)는 DB 에 저장하지 않는다 — xlsx 에만 담고 버린다.
 * <br>❌ order_item 테이블에 쓰지도 읽지도 않는다 (OrderSyncFacade 와 무관, 독립 read-through).
 * <br>대상 플랫폼: 쿠팡(COUPANG)만.
 *
 * <p>사용 예제 (Controller):
 * <pre>{@code
 * List<ShippingLabelRow> rows = shippingLabelService.collectRows(sellerId);
 * byte[] xlsx = shippingLabelService.toXlsx(rows);
 * }</pre>
 *
 * @see ShippingLabelRow
 */
public interface ShippingLabelService {

    /**
     * 대상 계정의 쿠팡 ordersheets(INSTRUCT)를 조회·펼쳐 행 목록을 수집한다.
     *
     * @param sellerId null 이면 활성 전체 계정, 지정 시 해당 셀러의 활성 계정만
     * @return 발주가능수량 > 0 인 상품 라인 행들
     */
    List<ShippingLabelRow> collectRows(Long sellerId);

    /**
     * 행 목록을 택배사 접수 xlsx bytes 로 변환한다 (1행 헤더, 2행부터 데이터).
     * 행 0건이어도 헤더만 있는 빈 시트를 반환한다.
     */
    byte[] toXlsx(List<ShippingLabelRow> rows);
}
