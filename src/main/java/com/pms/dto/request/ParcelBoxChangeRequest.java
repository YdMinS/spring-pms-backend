package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Changes the box of a parcel that is already packed (FEATURE_2611_06 / PLAN D8).
 *
 * <p>🔴 Only the box moves — stock out, contents, packed time and packer stay as they are (D4).
 *
 * @param boxPackageId the new box ({@code Package.id}), required
 */
public record ParcelBoxChangeRequest(@NotNull Long boxPackageId) {
}
