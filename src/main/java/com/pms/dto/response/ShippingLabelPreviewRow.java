package com.pms.dto.response;

import com.pms.service.ShippingLabelRow;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 송장 접수시트 편집용 미리보기 행 (V2 preview).
 *
 * <p>{@link ShippingLabelRow}(내부 모델)을 클라이언트 편집용으로 노출한다. {@code rowKey}
 * (shipmentBoxId:vendorItemId)로 편집분을 식별하며, {@code parcelQuantity}(택배수량)를
 * 사용자가 조정한 뒤 export 레그로 되돌려 xlsx 를 만든다.
 *
 * <p>record 사용 → accessor 자동 생성 (Lombok 금지).
 */
public record ShippingLabelPreviewRow(
        @Schema(description = "Edit-matching key, `shipmentBoxId:vendorItemId` combination",
                example = "302012345678:3823839899")
        String rowKey,
        String receiverName, String receiverPhone, String postCode, String address,
        String productName,
        @Schema(description = "Item quantity per line (내품수량)", example = "2")
        int quantity,
        @Schema(description = "Parcel quantity = number of boxes (택배수량)", example = "1")
        int parcelQuantity,
        String vendorItemId,
        String orderId, String deliveryMessage, String shipmentBoxId,
        String sellerName, String platform) {

    /** 내부 모델 → preview DTO 매핑 팩토리. */
    public static ShippingLabelPreviewRow from(ShippingLabelRow r) {
        return new ShippingLabelPreviewRow(
                r.rowKey(),
                r.receiverName(), r.receiverPhone(), r.postCode(), r.address(),
                r.productName(), r.quantity(), r.parcelQuantity(), r.vendorItemId(),
                r.orderId(), r.deliveryMessage(), r.shipmentBoxId(),
                r.sellerName(), r.platform());
    }
}
