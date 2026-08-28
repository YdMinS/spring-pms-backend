package com.pms.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Result of forcing the master's shipping settings onto every linked channel
 * (FEATURE_2608_06 / 77) — how many channel cells actually had their own override cleared.
 * Cells that were already inheriting are not counted (the operation is idempotent).
 */
@Getter
@Builder
public class ShippingForceApplyResponse {

    private int affectedChannels;
}
