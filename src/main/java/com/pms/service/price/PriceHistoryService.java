package com.pms.service.price;

import com.pms.domain.Platform;
import com.pms.domain.PriceTargetType;
import com.pms.dto.response.PriceChangeView;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads the price change history (FEATURE_2609_28 / PLAN D23). Read-only — the only writer is
 * {@link PriceHistoryRecorder}.
 */
public interface PriceHistoryService {

    /**
     * History rows, newest first. Every argument is optional and they combine with AND.
     *
     * <table>
     *   <tr><th>filter</th><th>answers</th></tr>
     *   <tr><td>productId</td><td>this purchased item's COST movements</td></tr>
     *   <tr><td>optionId</td><td>one cell option's selling price</td></tr>
     *   <tr><td>listingId</td><td>every option of ONE channel cell</td></tr>
     *   <tr><td>masterProductId</td><td>one product across ALL channels — "Coupang went up, Naver did not"</td></tr>
     *   <tr><td>platform</td><td>narrows the above to one channel</td></tr>
     *   <tr><td>targetType</td><td>cost only / selling price only</td></tr>
     * </table>
     *
     * <p>⚠️ {@code to} is inclusive for the caller: the service turns it into an exclusive bound at
     * the next midnight, so a change made late on that day is still returned.
     *
     * <p>⚠️ A cell with no master is unreachable by {@code masterProductId} (the FK is nullable) —
     * it is found by {@code listingId}. The screen has to say so.
     */
    List<PriceChangeView> search(Long productId, Long optionId, Long listingId, Long masterProductId,
                                 Platform platform, PriceTargetType targetType,
                                 LocalDate from, LocalDate to);
}
