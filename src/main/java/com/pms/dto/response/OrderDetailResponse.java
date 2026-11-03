package com.pms.dto.response;

import com.pms.domain.ParcelStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One order for the shared order detail screen (FEATURE_2611_07 / PLAN D17 · D27 · D30 · D37 · D38).
 *
 * <p>Both reads return this same shape — {@code GET /api/orders/lines/{orderLineId}/detail} (「주문내역」 · 「출고관리」) and
 * {@code GET /api/admin/packing/parcels/{id}/order} (「포장 작업」 「오늘 완료」). Only the lookup key and the role differ.
 *
 * <p>🔴 No phone, postcode or address — names only (D22, same rule as {@link OrderItemResponse}).
 *
 * @param externalOrderId the order number
 * @param sellerName      the seller of the order's account
 * @param platform        the sales channel ({@code Platform} name, e.g. {@code COUPANG})
 * @param accountAlias    the account alias, null when it has none — the screens write 「{channel} · {alias}」 or only
 *                        the channel (D35)
 * @param paidAt          {@code orders.ordered_at} as stored (already KST — D38 leaves it alone)
 * @param lines           every line of the order, id order, each the same fields as one order list row
 * @param parcels         every parcel of every shipment bundle of the order — bundle id order, then parcel sequence
 *                        (D29); empty when no invoice was issued yet (D26)
 */
public record OrderDetailResponse(
        String externalOrderId,
        String sellerName,
        String platform,
        String accountAlias,
        String ordererName,
        String receiverName,
        LocalDateTime paidAt,
        List<OrderItemResponse> lines,
        List<ParcelView> parcels) {

    /**
     * One parcel (invoice) of the order (D23 · D36 · D37).
     *
     * @param packedAt     the processed time as a KST wall clock (D38) — set for {@code PACKED} and {@code UNUSED},
     *                     null for {@code PENDING}
     * @param boxName      the box ({@code Package.type}) — only for {@code PACKED}, otherwise null
     * @param boxPackageId the id of that box — only for {@code PACKED}, otherwise null
     * @param items        what went into the box, merged per product — only for {@code PACKED}, otherwise empty
     */
    public record ParcelView(Long parcelId, String invoiceNumber, String carrierName, Integer parcelSeq,
                             ParcelStatus status, LocalDateTime packedAt, String boxName, Long boxPackageId,
                             List<ParcelItemView> items) {
    }

    /** One packed product: its photo ({@code Product.imageUrl} as stored), name and quantity. */
    public record ParcelItemView(Long productId, String productName, String imageUrl, int quantity) {
    }
}
