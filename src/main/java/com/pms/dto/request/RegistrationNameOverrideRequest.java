package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Registration-name (등록상품명, 쿠팡 {@code sellerProductName}) override body (65) for a channel cell. Sets
 * {@code ProductListing.registrationNameOverride} — blank is rejected and the length is capped at 255 to
 * match the Coupang field constraint. To clear the override (return to the auto-generated name, 32) call
 * {@code DELETE .../{id}/registration-name} instead. Mirrors {@link DisplayNameRequest} (35).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationNameOverrideRequest {

    @NotBlank
    @Size(max = 255)
    private String registrationName;
}
