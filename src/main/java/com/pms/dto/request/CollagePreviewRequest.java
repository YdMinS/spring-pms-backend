package com.pms.dto.request;

import com.pms.domain.CollageLayout;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Collage preview (FEATURE_2610_21 D20 · D46): the editor's unsaved {@code layout} drawn with the template of
 * {@code listingId}, or with the tenant default template when {@code listingId} is null.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CollagePreviewRequest {

    private Long listingId;

    private CollageLayout layout;
}
