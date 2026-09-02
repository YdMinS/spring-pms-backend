package com.pms.service;

import com.pms.dto.request.ShippingLabelExportRequest.ExportRow;
import com.pms.dto.response.ShippingLabelPreviewRow;

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
 * List<ShippingLabelPreviewRow> rows = shippingLabelService.previewRows(sellerId);
 * byte[] xlsx = shippingLabelService.toXlsxFromExport(editedRows);
 * }</pre>
 */
public interface ShippingLabelService {

    /**
     * 대상 계정의 쿠팡 ordersheets(INSTRUCT)를 조회·펼쳐 편집용 미리보기 행으로 반환한다 (V2).
     *
     * @param sellerId null 이면 활성 전체 계정, 지정 시 해당 셀러의 활성 계정만
     */
    List<ShippingLabelPreviewRow> previewRows(Long sellerId);

    /**
     * 주문 한 건(order_item.id)의 쿠팡 발주서를 단건 조회해 편집용 preview 행으로 반환한다.
     *
     * 목록 기반 previewRows 와 달리 status 조건이 없어 어떤 상태의 주문에서도 시트를 만들 수 있고,
     * 대상 범위는 그 주문번호(orderId)의 모든 박스·모든 라인이다(PLAN D2).
     *
     * @param orderItemId 우리 DB order_item PK (쿠팡 orderId 가 아님)
     * @throws com.pms.exception.ResourceNotFoundException 주문 라인 없음
     * @throws IllegalArgumentException 쿠팡 계정 주문이 아님
     * @throws IllegalStateException 쿠팡 조회/파싱 실패
     */
    List<ShippingLabelPreviewRow> previewRowsByOrder(Long orderItemId);

    /**
     * 사용자가 편집한 export rows 를 택배사 접수 xlsx bytes 로 변환한다 (V2).
     * 편집분(주로 parcelQuantity)을 그대로 반영한다.
     */
    byte[] toXlsxFromExport(List<ExportRow> rows);
}
