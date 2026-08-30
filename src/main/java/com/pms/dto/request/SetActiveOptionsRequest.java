package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bulk per-channel active-option set (FEATURE_2608_06 / 42). The UI sends the whole "active option set" for a
 * channel at once (avoids N individual toggle calls). The service sets {@code active = activeOptionIds.contains(id)}
 * for every option of the listing.
 *
 * <p>Business rules (enforced in the service): every id must belong to this listing (else 400), and the set must
 * be non-empty (at least one active option — an empty product is rejected 400).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The full set of option ids that should be active for a channel listing")
public class SetActiveOptionsRequest {

    @NotNull(message = "activeOptionIds cannot be null")
    @Schema(description = "Option ids to keep active (all others are deactivated)", example = "[1, 3]")
    private List<Long> activeOptionIds;
}
