package com.pms.dto.response;

/**
 * Purchase settings (FEATURE_2610_09 / D65 · D72).
 *
 * @param stockLinked true = a purchase is also added to stock; false = purchase record only (D65)
 */
public record PurchaseSettingResponse(boolean stockLinked) {
}
