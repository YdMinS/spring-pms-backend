package com.pms.dto.response;

import com.pms.domain.ParcelStatus;

/**
 * Result of closing a box as 「사용하지 않은 박스」 (FEATURE_2609_40 / PLAN D32) or of restoring it
 * (FEATURE_2611_03 / PLAN D13).
 *
 * <p>🔴 Closing as unused leaves <b>no</b> stock out, box recipe or savings — an unused label is not a packing
 * fact. A box closed by mistake is restored to {@code PENDING} through {@code POST /parcels/{id}/restore}
 * (PLAN 2611_03 D1); the remaining quantity never changed, so nothing else has to be undone.
 */
public record ParcelCloseResponse(Long parcelId, ParcelStatus status) {
}
