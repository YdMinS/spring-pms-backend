package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Per-channel option selling prices (FEATURE_2609_19). The UI saves the price boxes of one cell in a single
 * call rather than one request per option.
 *
 * <p>Like {@link SetOptionStocksRequest} this is a <b>partial</b> update: only the listed options are touched.
 * {@code sellingPrice: null} clears the manual price so the option goes back to the calculated one (D3) —
 * that is why the field is not {@code @NotNull}.</p>
 *
 * <p>Business rules (enforced in the service → 400): the list must be non-empty, every option id must belong
 * to the listing, and an option returning to AUTO must be priceable (category/fees/delivery/box/margin set).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Selling prices to apply to some options of a channel listing")
public class SetOptionPricesRequest {

    @NotEmpty(message = "변경할 옵션이 없습니다")
    @Valid
    @Schema(description = "The options to update (only these are touched)")
    private List<OptionPrice> prices;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One option's selling price")
    public static class OptionPrice {

        @NotNull(message = "optionId is required")
        @Schema(description = "Listing option id", example = "50")
        private Long optionId;

        @DecimalMin(value = "10", message = "판매가는 10원 이상이어야 합니다")
        @DecimalMax(value = "99999999", message = "판매가가 너무 큽니다")
        @Schema(description = "Manual selling price for this channel; null = back to the calculated price",
                nullable = true, example = "15000")
        private BigDecimal sellingPrice;
    }
}
