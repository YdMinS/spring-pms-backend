package com.pms.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Result of forcing the master's shipping settings onto the selected channels (FEATURE_2608_06 / 77,
 * semantics revised in 79) — how many channel cells actually changed. A cell whose shipping settings already
 * equalled the master's is not saved and not counted (the operation is idempotent).
 */
@Getter
@Builder
public class ShippingForceApplyResponse {

    private int affectedChannels;
}
