package com.pms.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Force-apply body (FEATURE_2608_06 / 79) — which channel cells the master's shipping settings are written
 * onto. {@code listingIds} holds product listing ids of this master's channels; {@code null} or an empty list
 * means <b>every</b> linked channel. The body itself is optional, so a bodyless POST keeps the original
 * "all channels" behaviour.
 *
 * <p>An id that is not one of this master's channels is rejected with 400 (a silent skip would look like a
 * successful apply).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShippingForceApplyRequest {

    private List<Long> listingIds;
}
