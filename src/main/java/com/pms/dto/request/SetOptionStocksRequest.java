package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-channel option stock quantities (FEATURE_2608_06 / 102). The UI saves the stock boxes of one cell in a
 * single call rather than one request per option.
 *
 * <p>Unlike {@link SetActiveOptionsRequest} this is <b>not</b> a whole-set replace: only the listed options are
 * updated, and each option's value is independent. {@code stockQuantity: null} clears the override (the option
 * goes back to inheriting the master option's stock).</p>
 *
 * <p>Business rules (enforced in the service → 400): the list must be non-empty, every option id must belong to
 * the listing, and no value may exceed that option's ceiling (master stock ?? 9999).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Stock quantities to apply to some options of a channel listing")
public class SetOptionStocksRequest {

    @NotEmpty(message = "변경할 옵션이 없습니다")
    @Valid
    @Schema(description = "The options to update (only these are touched)")
    private List<OptionStock> stocks;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One option's stock quantity")
    public static class OptionStock {

        @NotNull(message = "optionId is required")
        @Schema(description = "Listing option id", example = "50")
        private Long optionId;

        @Min(value = 0, message = "stockQuantity must be >= 0")
        @Max(value = 99999, message = "stockQuantity must be <= 99999")
        @Schema(description = "Stock for this channel; null = inherit the master option's stock",
                nullable = true, example = "30")
        private Integer stockQuantity;
    }
}
