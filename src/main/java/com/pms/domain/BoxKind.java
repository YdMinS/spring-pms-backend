package com.pms.domain;

/**
 * How we got the box (FEATURE_2609_40 / PLAN D20 · D21).
 *
 * <p>🔴 The distinction exists for ONE reason: a recycled box must never reach the selling-price
 * calculation. A recycled box costs 0, so if one could be picked as a master/option default box the
 * selling price would be computed from a zero-cost box.</p>
 *
 * <p>Two independent defences guard that, and neither replaces the other:</p>
 * <ul>
 *   <li>List filter — {@code GET /api/admin/package?boxKind=PURCHASED} feeds every pricing dropdown.</li>
 *   <li>Resolver rejection — {@link com.pms.service.MasterChannelConfigService#resolvePackage} throws
 *       400 when the already-assigned box is recycled.</li>
 * </ul>
 */
public enum BoxKind {

    /** A box we bought. Has a real cost and may be used for pricing. */
    PURCHASED,

    /** A box we reused (e.g. a carton something arrived in). Cost may be 0 and it is invisible to pricing. */
    RECYCLED
}
