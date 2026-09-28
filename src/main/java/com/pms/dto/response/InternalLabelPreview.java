package com.pms.dto.response;

import java.util.List;

/**
 * 「내부 상품준비중」 접수시트 미리보기 (E11, FEATURE_2609_75 / D26).
 *
 * @param rows                편집용 행 — 기존 V2 preview 와 같은 모양(엑셀은 기존 /v2/spreadsheet)
 * @param notAcceptedOrderIds 내부 상품준비중인데 쿠팡 결제완료 목록에 없던 주문번호(고객 취소·WING 처리 등)
 */
public record InternalLabelPreview(List<ShippingLabelPreviewRow> rows, List<String> notAcceptedOrderIds) {
}
