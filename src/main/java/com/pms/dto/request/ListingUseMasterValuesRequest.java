package com.pms.dto.request;

import com.pms.domain.ListingMarketValueGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [마스터 값으로 바꾸기] body (FEATURE_2611_27 / D20): the market-value group of a channel cell to switch back to the
 * master values.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Switch one market-value group of a channel cell back to the master values")
public class ListingUseMasterValuesRequest {

    @NotNull
    @Schema(description = "Group to switch back: ATTRIBUTES (attributes and notices), TAGS (search tags), SHIPPING",
            example = "TAGS")
    private ListingMarketValueGroup group;
}
