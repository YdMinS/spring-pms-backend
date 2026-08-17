package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Channel-add request (FEATURE_2608_06 / 15): register a new DRAFT {@link com.pms.domain.ProductListing}
 * cell for an unregistered (seller, platform) account under a master product.
 *
 * <p>The master is the single option universe — channel-add copies <em>all</em> of the master's options
 * (no subset selection). Per-platform price differences are handled by the price engine per cell, not by
 * option-set selection, so {@code optionIds} was removed in 15.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Add a channel cell (DRAFT listing) for a master product")
public class ChannelAddRequest {

    @NotNull(message = "Seller ID cannot be null")
    @Schema(description = "Seller ID (the account's seller)", example = "1")
    private Long sellerId;

    @NotBlank(message = "Platform cannot be blank")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;
}
