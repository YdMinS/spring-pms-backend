package com.pms.service.elevenst;

import com.pms.dto.request.ElevenstDeliveryDelayRequest;
import com.pms.dto.request.ElevenstVisitPickupRequest;
import com.pms.dto.response.ElevenstChoicesResponse;
import com.pms.dto.response.ElevenstDeliveryLineResponse;

import java.util.List;

/**
 * The 11st order-detail seller actions that are not a cancel request, a claim or a shipment upload
 * (FEATURE_2610_17 / D63 · D64 · D66 · D67). Every write cannot be undone on 11st and is user-pressed only.
 */
public interface ElevenstOrderSellerService {

    /** The code lists of [주문 취소] · [책임사유 오류로 거부] · [발송지연 안내] (D61 · D62 · D63). */
    ElevenstChoicesResponse choices();

    /**
     * Lines of the same order and the same 11st delivery as {@code orderItemId} (D64 — the partial / split pickers).
     *
     * @throws IllegalArgumentException not an 11st line with a delivery number (→ 400)
     */
    List<ElevenstDeliveryLineResponse> deliveryLines(Long orderItemId);

    /**
     * [발송지연 안내] 6728 for the line's delivery (D63).
     *
     * @throws IllegalArgumentException not a paid / preparing 11st line · date out of range · unknown reason ·
     *                                  detail text outside 20~300 EUC-KR bytes (→ 400)
     */
    void deliveryDelay(Long orderItemId, ElevenstDeliveryDelayRequest request);

    /**
     * [방문수령 완료] 7052 (D66) — success marks every line of the delivery {@code DELIVERED}.
     *
     * @throws IllegalArgumentException not a paid / preparing 11st visit-pickup line (→ 400)
     */
    void visitPickup(Long orderItemId, ElevenstVisitPickupRequest request);

    /**
     * [입고완료 처리] 1773 of a reserved-sale line (D67) — success stores the sent time; the raw status is left to the
     * next sync.
     *
     * @throws IllegalArgumentException not a reserved-sale 11st line · already sent (→ 400)
     */
    void stockConfirm(Long orderItemId);
}
