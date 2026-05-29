package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO for commission rate queries.
 *
 * Contains all commission rate data returned from API endpoints.
 * Maps 1:1 with CommissionRate entity fields.
 *
 * @see CommissionRateRequest
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Commission rate response")
public class CommissionRateResponse {

    @Schema(
        description = "Unique identifier for the commission rate",
        example = "1"
    )
    private Long id;

    @Schema(
        description = "Platform name",
        example = "COUPANG"
    )
    private String platform;

    @Schema(
        description = "Category ID (null indicates platform default rate)",
        example = "5",
        nullable = true
    )
    private Long categoryId;

    @Schema(
        description = "Commission rate as decimal (0.00 - 1.00). " +
                      "Example: 0.05 = 5% commission",
        example = "0.05"
    )
    private BigDecimal rate;

    @Schema(
        description = "Flag indicating if this is the default rate for the platform",
        example = "true"
    )
    private Boolean isDefault;

    @Schema(
        description = "Category name (null if platform default rate)",
        example = "Electronics",
        nullable = true
    )
    private String categoryName;
}
