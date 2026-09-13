package com.pms.service.packing;

import com.pms.domain.BoxKind;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A remembered box offered for the combination currently in hand (FEATURE_2609_40 / PLAN D23).
 *
 * <p>Carries everything the packing screen needs to show a box without a second round trip: the photo when
 * there is one, and the dimensions to draw a shape when there is not (D26).</p>
 *
 * @param packageId box id
 * @param type      box name/type as shown in box management
 * @param boxKind   PURCHASED or RECYCLED — recycled boxes are legitimate here, only pricing excludes them
 * @param cost      box cost (0 is normal for a recycled box)
 * @param widthCm   box width in cm (0 = unset)
 * @param lengthCm  box length in cm (0 = unset)
 * @param heightCm  box height in cm (0 = unset)
 * @param imageUrl  box photo, or null -> the screen draws a shape from the dimensions (D26)
 * @param useCount  how many times this combination went into this box (the ordering key)
 * @param lastUsedAt when it was last used (tie-breaker, and the default pick is the most recent)
 */
public record BoxCandidate(
        Long packageId,
        String type,
        BoxKind boxKind,
        BigDecimal cost,
        BigDecimal widthCm,
        BigDecimal lengthCm,
        BigDecimal heightCm,
        String imageUrl,
        Integer useCount,
        LocalDateTime lastUsedAt
) {
}
