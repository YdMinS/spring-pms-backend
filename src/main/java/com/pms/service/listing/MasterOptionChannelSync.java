package com.pms.service.listing;

import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;

/**
 * Master option → channel cell <b>structure</b> sync (FEATURE_2608_06 / 86) — the single place that keeps
 * "a channel always exists; an added option is simply added to it" true in code.
 *
 * <p>Before 86 a {@link com.pms.domain.ProductListingOption} row was only ever born in the channel-add copy
 * ({@code ChannelAddServiceImpl}), and propagation deliberately moved <em>quantities only</em>. So an option
 * added to a master that already had channels never reached them, and an option deleted from the master
 * stayed behind on every channel — active, with a stale price, still riding along in the next push payload.
 * These four hooks close that gap.</p>
 *
 * <h3>Rules that MUST NOT be re-implemented elsewhere</h3>
 * <ul>
 *   <li><b>A newly propagated option is {@code active=false} on every channel</b> (DRAFT and market-registered
 *       alike). The option exists but sells nowhere until a human ticks its column in the matrix: an option must
 *       never quietly join the next push payload, and inactive + no market id = not locked (84), so it stays
 *       editable right up to the moment somebody switches it on. ⚠️ This differs from channel <em>creation</em>
 *       ({@code ChannelAddServiceImpl}, entity {@code @Builder.Default active=true}) — that path is unchanged.</li>
 *   <li><b>A channel option row is never physically deleted</b> (42, {@code decisions/backend/DECISIONS.md}).
 *       Removal and orphan cleanup only flip {@code active=false}: an inactive option is already excluded from
 *       the push payload ({@code CoupangListingAdapter} items[] = ACTIVE only), channel options may have been
 *       hand-made through the legacy path (indistinguishable from leftovers), and the row must survive for
 *       re-activation, order mapping and {@code fetchStatus} name matching.</li>
 *   <li><b>The BOM lines under an option ARE rebuilt</b> — they are derived from the master. A re-added option
 *       reuses its existing channel row but has its lines replaced from the master items, never merged: reusing
 *       {@link OptionQuantitySync} here would only touch products present on both sides and leave a deleted
 *       option's stale composition (and therefore a wrong cost and price) in place.</li>
 * </ul>
 *
 * <p>⚠️ No {@code @Transactional} anywhere in this component — every method joins the caller's transaction
 * (master option CRUD's {@code @Transactional}, or {@code propagateOne}'s {@code REQUIRES_NEW} per-cell
 * boundary), so a failed sync rolls the master edit back with it.</p>
 *
 * <p>Usage — {@code MasterProductServiceImpl} wires the three master-scoped hooks into option CRUD:</p>
 * <pre>{@code
 * // createOption: after the master option is persisted
 * masterOptionChannelSync.onOptionCreated(masterId, option);
 * // deleteOption: after the 84 guards pass, BEFORE the master option row goes away (the name is needed)
 * masterOptionChannelSync.onOptionRemoved(masterId, option.getName());
 * }</pre>
 *
 * <p>❌ Do not call the master-scoped hooks from a per-cell loop — see {@link #syncStructure(ProductListing)}.</p>
 *
 * @see MasterOptionChannelSyncImpl
 * @see OptionQuantitySync quantities only; this component owns structure
 */
public interface MasterOptionChannelSync {

    /**
     * A master option was created → give every cell of that master the option: a new row
     * ({@code active=false}) where it is missing, or a BOM rebuild where a same-named row already exists
     * (a re-added option reuses the old row, {@code active} untouched).
     *
     * @param masterId the master whose cells receive the option
     * @param option   the freshly persisted master option (its items are the BOM source)
     */
    void onOptionCreated(Long masterId, MasterProductOption option);

    /**
     * A master option was renamed → cascade {@code oldName → newName} onto every cell option still carrying
     * the old name. {@code optionName} is the master↔channel match key, so skipping this would strand the
     * channel copies permanently unmatched with stale prices.
     *
     * <p>Defensive: a cell that already has a row under {@code newName} is left alone with a WARN — the
     * master-level uniqueness guard makes that unreachable on the normal path, but legacy channel rows may
     * carry duplicates.</p>
     */
    void onOptionRenamed(Long masterId, String oldName, String newName);

    /**
     * A master option is about to be deleted → switch the same-named option off ({@code active=false}) on
     * every cell. Rows and BOM lines are kept (see the class note); prices of the remaining options do not
     * move, so no price recalculation is triggered.
     *
     * <p>⚠️ Call this <b>before</b> deleting the master option row — only the name matches the channel copies.</p>
     */
    void onOptionRemoved(Long masterId, String optionName);

    /**
     * Propagation entry point — reconcile ONE cell against its master's current option set: create what is
     * missing ({@code active=false}), switch off orphans that the master no longer has.
     *
     * <p>⚠️ An active orphan on a market-registered cell ({@code platformProductId != null}) is left untouched
     * with a WARN: it is really on sale on the market, and switching it off locally only desynchronises the
     * screen from the market. A human stops it in WING.</p>
     *
     * <p>❌ {@code propagateOne} must call THIS, never the master-scoped hooks: it runs once per cell in its own
     * transaction, so a master-scoped call there would walk all N cells N times (O(N²) price recalcs) and write
     * other cells inside this cell's transaction.</p>
     */
    void syncStructure(ProductListing cell);
}
