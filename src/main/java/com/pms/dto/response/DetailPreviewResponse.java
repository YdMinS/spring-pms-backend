package com.pms.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Non-persistent AUTO preview of a cell's detail HTML (FEATURE_2608_06 / Step 2-2). Rendered from the
 * current master + default template — ignores any {@code MANUAL_OVERRIDE} (for comparison in the editor).
 */
@Getter
@Builder
public class DetailPreviewResponse {

    private String html;
}
