package com.pms.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * "옵션확인" suffix config body (FEATURE_2608_06 / 69) for all three levels (seller / marketplace account /
 * master product). <b>Replace semantics</b>: the value sent is stored as-is, {@code null} = inherit (falls
 * through to the next resolution level). Both fields are nullable and independent.
 *
 * <p>⚠️ Not a keep-existing PATCH: sending only one field overwrites the other with null (inherit). The front
 * must pre-fill both current override values and always send them together (change {@code enabled} alone →
 * re-send {@code suffix}). The response prefill fields are the source for that.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptionCheckSuffixRequest {

    /** null = inherit; true/false = this level's explicit toggle. */
    private Boolean enabled;

    /** null/blank = inherit; else the custom suffix text (trimmed on save). */
    @Size(max = 50)
    private String suffix;
}
