package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-channel option names (FEATURE_2609_22 / D3). A channel may name its options itself — the market may
 * already show that name, and since 2609_22/D1 the master↔channel link is an FK, so renaming no longer
 * breaks it.
 *
 * <p>Partial write, like {@link SetOptionStocksRequest}: only the listed options are touched.
 * {@code optionName: null} (or blank) restores the linked master option's name and marks the option
 * {@code AUTO} again — which requires a linked master option (a channel-only option has no name to go back
 * to → 400).</p>
 *
 * <p>Business rules (enforced in the service → 400): the list must be non-empty, every option id must belong
 * to the listing, and the resulting names must be unique within the cell (a duplicate Coupang
 * {@code itemName} is a marketplace error). Nothing is saved when any of them fails.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Option names to apply to some options of a channel listing")
public class SetOptionNamesRequest {

    @NotEmpty(message = "변경할 옵션이 없습니다")
    @Valid
    @Schema(description = "The options to rename (only these are touched)")
    private List<Item> names;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One option's name")
    public static class Item {

        @NotNull(message = "optionId is required")
        @Schema(description = "Listing option id", example = "50")
        private Long optionId;

        @Size(max = 255, message = "optionName must be <= 255 characters")
        @Schema(description = "Name for this channel; null/blank = go back to the master option's name",
                nullable = true, example = "생수 6개입")
        private String optionName;
    }
}
