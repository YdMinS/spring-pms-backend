package com.pms.service.listing;

import com.pms.domain.Platform;

import java.util.Map;

/**
 * Per-platform tag count caps (33). Coupang's {@code searchTags} caps at 20; Naver's limit is not yet
 * confirmed, so {@link #DEFAULT} is a temporary placeholder equal to Coupang's (re-tuned in a follow-up).
 * Kept as a constant map — a single seam for adding Naver etc. later without touching the merge logic.
 */
public final class TagLimits {

    /** Fallback cap for platforms not listed in {@link #BY_PLATFORM} (Naver TBD → temporary 20). */
    public static final int DEFAULT = 20;

    /** Confirmed per-platform caps. */
    // FEATURE_2610_13 / D57: 11st takes no search tags from a regular seller → 0 (the merge sends an empty list).
    // FEATURE_2610_26 / D34: ESM has no search-word field on the goods body → 0 as well.
    public static final Map<Platform, Integer> BY_PLATFORM =
            Map.of(Platform.COUPANG, 20, Platform.ELEVENST, 0, Platform.ESM, 0);

    private TagLimits() {
    }
}
