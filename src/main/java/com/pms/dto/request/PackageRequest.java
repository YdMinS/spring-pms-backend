package com.pms.dto.request;

import com.pms.domain.BoxKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for package creation/update. All fields required.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageRequest {

    @NotBlank(message = "Package type is required")
    @Schema(description = "Package type/category", example = "STANDARD", maxLength = 50)
    private String type;

    // 🔴 Cost 0 is accepted HERE and rejected in the service for a PURCHASED box (PLAN 2609_40 D20):
    // the rule depends on boxKind, which a field-level annotation cannot see. A recycled box legitimately
    // costs 0 — refusing it at this layer would make recycled boxes impossible to register.
    @NotNull(message = "Cost is required")
    @DecimalMin(value = "0.00")
    @Schema(description = "Shipping cost (0 allowed only for a RECYCLED box)", example = "15.50", type = "number")
    private BigDecimal cost;

    @NotNull(message = "Effective date is required")
    @Schema(description = "Date from which package is valid (ISO)", example = "2026-05-16", format = "date")
    private LocalDate effectiveDate;

    @NotNull(message = "isDefault flag is required")
    @Schema(description = "Is default package type? (Only one can be true)", example = "false")
    private Boolean isDefault;

    // Box dimensions in cm (PLAN 2609_38 D5). 0 / 1000 / 22.55 are all 400:
    // @DecimalMin blocks the "unset" 0 that the backfill left behind, @DecimalMax caps the range
    // (DECIMAL(5,1) itself would accept up to 9999.9) and @Digits blocks the second decimal that the
    // DB would otherwise round silently (22.55 -> 22.6).
    @NotNull(message = "Width is required")
    @DecimalMin(value = "0.1", message = "Width must be at least 0.1cm")
    @DecimalMax(value = "999.9", message = "Width must not exceed 999.9cm")
    @Digits(integer = 3, fraction = 1, message = "Width allows one decimal place")
    @Schema(description = "Box width in cm", example = "22.0", type = "number")
    private BigDecimal widthCm;

    @NotNull(message = "Length is required")
    @DecimalMin(value = "0.1", message = "Length must be at least 0.1cm")
    @DecimalMax(value = "999.9", message = "Length must not exceed 999.9cm")
    @Digits(integer = 3, fraction = 1, message = "Length allows one decimal place")
    @Schema(description = "Box length in cm", example = "19.0", type = "number")
    private BigDecimal lengthCm;

    @NotNull(message = "Height is required")
    @DecimalMin(value = "0.1", message = "Height must be at least 0.1cm")
    @DecimalMax(value = "999.9", message = "Height must not exceed 999.9cm")
    @Digits(integer = 3, fraction = 1, message = "Height allows one decimal place")
    @Schema(description = "Box height in cm", example = "9.0", type = "number")
    private BigDecimal heightCm;

    /**
     * How we got the box (PLAN 2609_40 D20). Optional: omitted / null means {@link BoxKind#PURCHASED},
     * which keeps clients that predate this feature creating bought boxes exactly as before.
     *
     * <p>🔴 A RECYCLED box may cost 0 but can never be the default box — the default box is what the
     * selling-price calculation falls back to (D21).</p>
     *
     * <p>⚠️ There is no image field here on purpose: the photo is owned by
     * {@code POST /api/admin/package/{id}/image} and an edit must never wipe it.</p>
     */
    @Schema(description = "Box kind (PURCHASED | RECYCLED). Null = PURCHASED", example = "PURCHASED")
    private BoxKind boxKind;
}
