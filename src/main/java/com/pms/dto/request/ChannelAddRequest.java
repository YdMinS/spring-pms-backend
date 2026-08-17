package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Channel-add request (FEATURE_2608_06 / 3b'): register a new DRAFT {@link com.pms.domain.ProductListing}
 * cell for an unregistered (seller, platform) account under a master product. {@code optionIds} is the
 * subset of the master's options to copy into listing options + BOM (accounts may pick different subsets).
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

    @NotNull(message = "Platform cannot be null")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotEmpty(message = "Option IDs cannot be empty")
    @Schema(description = "Master option IDs to copy into this cell (subset allowed)", example = "[1, 2]")
    private List<Long> optionIds;
}
