package com.pms.service.packing;

import java.util.List;

/**
 * The box memory (FEATURE_2609_40 / PLAN D22 · D23 · D24 · D25).
 *
 * <p>🔴 The ONLY window onto {@code box_recipe}. Every method routes through {@link BoxRecipeKey} so the
 * recording side and the lookup side can never disagree about what "the same combination" means.</p>
 *
 * <p>⚠️ A future volume-based suggestion must not override this: a remembered box always wins and the
 * calculation only runs when there is no memory (D25). Someone deciding "this combination ships in that box"
 * is a decision, not a guess.</p>
 */
public interface BoxRecipeService {

    /**
     * Box candidates for the combination in hand — most used first, most recently used on a tie, at most 3
     * (PLAN D23).
     *
     * <p>Returns an EMPTY LIST when nothing was ever packed like this; that is not an error, the screen
     * simply says "적합한 상자를 찾지 못했습니다".</p>
     *
     * @param items what is in the box (order irrelevant, repeated products are merged)
     * @return up to 3 candidates, best first; empty when unknown
     */
    List<BoxCandidate> candidates(List<RecipeItem> items);

    /**
     * Record that this combination went into this box (PLAN D24). Called when a parcel is completed and when a
     * packed parcel's box changes (FEATURE_2611_06 / PLAN D5).
     * Existing memory gets {@code use_count + 1} and a fresh {@code last_used_at}; otherwise a row with
     * {@code use_count = 1} is created.
     *
     * <p>⚠️ No filtering happens here — a recycled box is remembered exactly like a bought one. Deciding
     * what is still usable is {@link #candidates}' job, on the way out.</p>
     *
     * @param items     what was in the box (empty = nothing to remember, no-op)
     * @param packageId the box that was actually used
     */
    void remember(List<RecipeItem> items, Long packageId);

    /**
     * Take back one use of this combination in this box (FEATURE_2611_06 / PLAN D5). Called when a packed parcel's
     * box is changed: {@code use_count - 1}, and the row is deleted when that reaches 0. {@code last_used_at} stays.
     *
     * <p>⚠️ No row = nothing to take back (the memory failed when the parcel was completed) — a no-op, not an
     * error.</p>
     *
     * @param items     what is in the box (empty = no-op)
     * @param packageId the box the parcel had before the change
     */
    void forget(List<RecipeItem> items, Long packageId);
}
