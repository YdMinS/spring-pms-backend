package com.pms.service.listing;

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
    public static final Map<String, Integer> BY_PLATFORM = Map.of("COUPANG", 20);

    private TagLimits() {
    }
}
