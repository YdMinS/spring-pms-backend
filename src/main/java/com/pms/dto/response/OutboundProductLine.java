package com.pms.dto.response;

/**
 * One product an outbound order line consumes (FEATURE_2609_28 / PLAN D11·D13).
 *
 * @param requiredQty  master BOM quantity × order quantity
 * @param confirmedQty the {@code STOCK_OUT} sum already recorded — <b>positive</b>
 * @param imageUrl     the product's representative photo ({@code product.image_url}) as stored; null = none
 *                     (FEATURE_2611_02 D8 · D9)
 */
public record OutboundProductLine(Long productId, String productName, int requiredQty, int confirmedQty,
                                  String imageUrl) {

    /** 아직 내보내지 않은 수량. 화면이 다시 빼지 않도록 서버가 계산해 준다. */
    public int remainingQty() {
        return requiredQty - confirmedQty;
    }
}
