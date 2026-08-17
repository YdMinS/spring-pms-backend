package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Raw detail-HTML override body (FEATURE_2608_06 / Step 2-2). The empty string is allowed (an intentional
 * "no detail" edit) — only null is rejected, so no blank check.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DetailHtmlOverrideRequest {

    @NotNull
    private String html;
}
