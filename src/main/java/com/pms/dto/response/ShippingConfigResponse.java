package com.pms.dto.response;

import com.pms.domain.MarketplaceShippingConfig;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Response for a marketplace account's shipping config (FEATURE_2608_06 / 72). When no config is stored yet,
 * {@link #empty} returns an all-null response (200, not 404) so the front shows an unfilled form.
 */
@Getter
@Builder
public class ShippingConfigResponse {

    private Long marketplaceAccountId;

    // outbound place
    private String outboundShippingPlaceCode;

    // return center (full address block)
    private String returnCenterCode;
    private String returnChargeName;
    private String returnContactNumber;
    private String returnZipCode;
    private String returnAddress;
    private String returnAddressDetail;
    private BigDecimal returnCharge;
    private BigDecimal deliveryChargeOnReturn;

    // delivery settings
    private String deliveryMethod;
    private String deliveryCompanyCode;
    private String deliveryChargeType;
    private BigDecimal deliveryCharge;
    private BigDecimal freeShipOverAmount;
    private String remoteAreaDeliverable;
    private String unionDeliveryType;

    // extra info message (주문제작/설치배송 추가정보), 75
    private String extraInfoMessage;

    public static ShippingConfigResponse from(MarketplaceShippingConfig c) {
        return ShippingConfigResponse.builder()
                .marketplaceAccountId(c.getMarketplaceAccount().getId())
                .outboundShippingPlaceCode(c.getOutboundShippingPlaceCode())
                .returnCenterCode(c.getReturnCenterCode())
                .returnChargeName(c.getReturnChargeName())
                .returnContactNumber(c.getReturnContactNumber())
                .returnZipCode(c.getReturnZipCode())
                .returnAddress(c.getReturnAddress())
                .returnAddressDetail(c.getReturnAddressDetail())
                .returnCharge(c.getReturnCharge())
                .deliveryChargeOnReturn(c.getDeliveryChargeOnReturn())
                .deliveryMethod(c.getDeliveryMethod())
                .deliveryCompanyCode(c.getDeliveryCompanyCode())
                .deliveryChargeType(c.getDeliveryChargeType())
                .deliveryCharge(c.getDeliveryCharge())
                .freeShipOverAmount(c.getFreeShipOverAmount())
                .remoteAreaDeliverable(c.getRemoteAreaDeliverable())
                .unionDeliveryType(c.getUnionDeliveryType())
                .extraInfoMessage(c.getExtraInfoMessage())
                .build();
    }

    /** All-null config (nothing stored yet) for the given account. */
    public static ShippingConfigResponse empty(Long marketplaceAccountId) {
        return ShippingConfigResponse.builder()
                .marketplaceAccountId(marketplaceAccountId)
                .build();
    }

    /**
     * Resolved shipping (FEATURE_2608_06 / 76) — a cell's inherited baseline (master ?? account) or fully
     * resolved config. Not tied to one account, so {@code marketplaceAccountId} is null.
     */
    public static ShippingConfigResponse from(com.pms.service.listing.shipping.ResolvedShippingConfig r) {
        return ShippingConfigResponse.builder()
                .outboundShippingPlaceCode(r.outboundShippingPlaceCode())
                .returnCenterCode(r.returnCenterCode())
                .returnChargeName(r.returnChargeName())
                .returnContactNumber(r.returnContactNumber())
                .returnZipCode(r.returnZipCode())
                .returnAddress(r.returnAddress())
                .returnAddressDetail(r.returnAddressDetail())
                .returnCharge(r.returnCharge())
                .deliveryChargeOnReturn(r.deliveryChargeOnReturn())
                .deliveryMethod(r.deliveryMethod())
                .deliveryCompanyCode(r.deliveryCompanyCode())
                .deliveryChargeType(r.deliveryChargeType())
                .deliveryCharge(r.deliveryCharge())
                .freeShipOverAmount(r.freeShipOverAmount())
                .remoteAreaDeliverable(r.remoteAreaDeliverable())
                .unionDeliveryType(r.unionDeliveryType())
                .extraInfoMessage(r.extraInfoMessage())
                .build();
    }
}
