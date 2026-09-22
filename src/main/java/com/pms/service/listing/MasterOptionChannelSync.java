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
 *   <li><b>구성품은 복사하지 않는다</b>(2609_71) — 셀 옵션의 구성품은 {@code master_product_option_id} FK 를
 *       타고 마스터 옵션의 items 에서 곧바로 읽는다({@code CellBomResolver}). 「재추가된 옵션에 옛 구성이
 *       남는다」는 문제 자체가 없고, 이 컴포넌트는 옵션 행이 마스터 옵션을 제대로 가리키는지만 책임진다.</li>
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
 * // deleteOption: after the 84 guards pass, BEFORE the master option row goes away (the FK is SET NULL then)
 * masterOptionChannelSync.onOptionRemoved(masterId, option.getId());
 * }</pre>
 *
 * <p>❌ Do not call the master-scoped hooks from a per-cell loop — see {@link #syncStructure(ProductListing)}.</p>
 *
 * @see MasterOptionChannelSyncImpl
 */
public interface MasterOptionChannelSync {

    /**
     * A master option was created → give every cell of that master the option: a new row
     * ({@code active=false}) where it is missing. A cell that already has a row linked to this master option
     * keeps it untouched (a re-added option reuses the old row, {@code active} untouched) — its 구성품은
     * FK 를 타고 자동으로 새 items 를 따른다(2609_71).
     *
     * @param masterId the master whose cells receive the option
     * @param option   the freshly persisted master option
     */
    void onOptionCreated(Long masterId, MasterProductOption option);

    /**
     * A master option was renamed → push {@code newName} onto every cell option <b>linked to it</b>
     * (2609_22/D1: the match key is {@code master_product_option_id}, never the name).
     *
     * <p>⚠️ 2609_22/D4: a cell option whose {@code optionNameSource} is {@code MANUAL_OVERRIDE} keeps its own
     * name — the channel named it deliberately (it may be the name the marketplace already shows). The master
     * detail's [옵션명 일괄 적용] is what pulls those back to the master name.</p>
     *
     * <p>Defensive: a cell that already has a row under {@code newName} is left alone with a WARN — a
     * MANUAL_OVERRIDE sibling may legitimately hold that name, and duplicate names within one cell are a
     * marketplace error (Coupang {@code itemName}).</p>
     *
     * @param masterOptionId the renamed master option's id (the match key)
     */
    void onOptionRenamed(Long masterId, Long masterOptionId, String newName);

    /**
     * A master option is about to be deleted → switch every cell option <b>linked to it</b> off
     * ({@code active=false}). Rows are kept (see the class note); prices of the remaining options do not
     * move, so no price recalculation is triggered.
     *
     * <p>⚠️ Call this <b>before</b> deleting the master option row: the FK is {@code ON DELETE SET NULL}
     * (changeset 060), so afterwards nothing points at it any more. After the delete those rows are
     * channel-only + inactive (D22) — which is the truth: the marketplace still carries them.</p>
     *
     * @param masterOptionId the master option about to go away (the match key)
     */
    void onOptionRemoved(Long masterId, Long masterOptionId);

    /**
     * 마스터 옵션의 <b>구성(상품 집합)</b> 이 바뀌었다 → 이 옵션에 연결된 셀들의 <b>판매가를 다시 계산</b>한다
     * (2609_64 + 2609_71).
     *
     * <p>2609_71: 셀에 구성품 사본이 없으므로 교체할 줄이 없다 — 구성품은 FK 를 타고 마스터 옵션의 items 를
     * 그대로 따른다. 남는 일은 바뀐 원가 합을 판매가에 반영하는 것뿐이다({@code recalculateOptionPrices}).
     * 마켓 재승인 표시는 여기서 하지 않는다 — 호출부(2609_64 서비스)가 셀 단위로 판단한다.</p>
     *
     * <p>⚠️ 이 옵션을 갖지 않은 셀에는 행을 만들지 않는다. "구성이 바뀌었다"이지 "옵션이 생겼다"가 아니다 —
     * 그 경우는 {@link #onOptionCreated}/{@link #syncStructure} 소관이다.</p>
     *
     * @param option 이미 새 items 로 저장된 마스터 옵션
     */
    void onOptionComponentsChanged(Long masterId, MasterProductOption option);

    /**
     * Propagation entry point — reconcile ONE cell against its master's current option set: create what is
     * missing ({@code active=false}), switch off orphans that the master no longer has.
     *
     * <p>⚠️ 2609_22/D2: a <b>channel-only</b> option ({@code masterProductOption == null}) is never an orphan —
     * it is deliberately absent from the master and is left completely alone.</p>
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
