package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Batch channel-add request (FEATURE_2608_06 / 15): register several unregistered (seller, platform)
 * accounts under one master in a single call.
 *
 * <p>The frontend fills {@code targets} from the matrix's unregistered rows; the backend processes only the
 * explicit targets (it never re-queries the account list). Each target is processed independently — a failure
 * (duplicate 409, missing category 400, …) fails only that cell, not the whole batch.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Add multiple channel cells (DRAFT listings) for a master product")
public class BatchChannelAddRequest {

    @NotEmpty(message = "Targets cannot be empty")
    @Valid
    @Schema(description = "Unregistered (seller, platform) accounts to register")
    private List<Target> targets;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "A single (seller, platform) account to register")
    public static class Target {

        @NotNull(message = "Seller ID cannot be null")
        @Schema(description = "Seller ID (the account's seller)", example = "1")
        private Long sellerId;

        @NotBlank(message = "Platform cannot be blank")
        @Schema(description = "Platform identifier", example = "COUPANG")
        private String platform;
    }
}
