package com.pms.service.packing;

import java.util.List;

/**
 * The box memory (FEATURE_2609_40 / PLAN D22 · D23 · D24 · D25).
 *
 * <p>🔴 The ONLY window onto {@code box_recipe}. Both methods route through {@link BoxRecipeKey} so the
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
     * Record that this combination went into this box (PLAN D24). Called when a parcel is completed.
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
}
