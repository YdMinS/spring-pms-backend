package com.pms.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Upsert request for a marketplace account's shipping config (FEATURE_2608_06 / 72). Every field of the stored
 * entity, all optional — partial save is allowed while the wizard fills in. Register (73) is the final guard for
 * missing required values, so validation here is minimal. The chosen value may come from a platform lookup or
 * from manual entry — both take this same path.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShippingConfigRequest {

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

    // delivery settings (seller-chosen)
    private String deliveryMethod;
    private String deliveryCompanyCode;
    private String deliveryChargeType;
    private BigDecimal deliveryCharge;
    private BigDecimal freeShipOverAmount;
    /** "Y"/"N" (String, not Boolean — BIT trap). */
    private String remoteAreaDeliverable;
    private String unionDeliveryType;
}
