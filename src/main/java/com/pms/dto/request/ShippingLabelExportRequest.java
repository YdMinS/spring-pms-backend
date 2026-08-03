package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 송장 접수시트 export 요청 (V2 spreadsheet) — 사용자가 편집한 rows 를 그대로 xlsx 로 생성한다.
 *
 * <p>preview 로 받은 행을 편집(주로 {@code parcelQuantity} 조정) 후 posted rows 그대로 전송한다.
 * rowKey/vendorItemId 는 xlsx 에 미출력이라 재수신하지 않는다(비대칭 의도).
 *
 * <p>record 사용 → accessor 자동 생성 (Lombok 금지).
 */
public record ShippingLabelExportRequest(
        @Valid @NotEmpty List<ExportRow> rows
) {

    /** 편집된 접수시트 한 행. */
    public record ExportRow(
            @NotNull String receiverName,
            @NotNull String receiverPhone,
            @NotNull String postCode,
            @NotNull String address,
            @NotNull String productName,
            @Min(0) int quantity,
            @Schema(description = "Number of shipping labels (택배 라벨 수), minimum 1", example = "1")
            @Min(1) int parcelQuantity,          // 핵심 검증: 0/음수 차단
            String orderId,
            String deliveryMessage,              // nullable 허용 (빈값 가능)
            String shipmentBoxId,
            String sellerName,
            String platform
    ) {}
}
