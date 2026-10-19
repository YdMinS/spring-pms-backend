package com.pms.dto.request;

import com.pms.domain.CollageLayout;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Save one collage stage (FEATURE_2610_21 D9 · D42). {@code enabled = true} needs a valid {@code layout};
 * {@code enabled = false} = "switch the collage off at this stage" and the layout is not stored.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CollageSaveRequest {

    private Boolean enabled;

    private CollageLayout layout;
}
