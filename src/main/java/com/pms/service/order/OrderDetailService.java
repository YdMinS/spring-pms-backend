package com.pms.service.order;

import com.pms.dto.response.OrderDetailResponse;

/**
 * The shared order detail read (FEATURE_2611_07 / PLAN D17 · D30).
 *
 * <p>Two keys, one answer: an order line id (「주문내역」 · 「출고관리」 — the line the user clicked) and a parcel id
 * (「포장 작업」 「오늘 완료」 — the parcel the user clicked). Both find the order and build the same
 * {@link OrderDetailResponse}. Read-only — nothing is written.
 *
 * <p>Usage (Controller):
 * <pre>
 *   orderDetailService.byOrderLine(orderLineId);   // GET /api/orders/lines/{orderLineId}/detail
 *   orderDetailService.byParcel(parcelId);         // GET /api/admin/packing/parcels/{id}/order
 * </pre>
 *
 * @see com.pms.controller.OrderController
 * @see com.pms.controller.PackingController
 */
public interface OrderDetailService {

    /** The order the line belongs to. Unknown line = 404. */
    OrderDetailResponse byOrderLine(Long orderLineId);

    /** The order the parcel belongs to. Unknown parcel = 404. */
    OrderDetailResponse byParcel(Long parcelId);
}
