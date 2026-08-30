package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Display-name (노출상품명) update body (35) for a channel cell. The name maps to
 * {@code ProductListing.name} (NOT NULL VARCHAR(255)) — blank is rejected and the length is capped at
 * 255 to keep in step with the create-side constraint and avoid a DB violation. Internal only: this is
 * not pushed to the marketplace.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisplayNameRequest {

    @NotBlank
    @Size(max = 255)
    private String name;
}
