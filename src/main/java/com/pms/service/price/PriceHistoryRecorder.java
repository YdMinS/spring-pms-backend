package com.pms.service.price;

import com.pms.domain.ListingStatus;
import com.pms.domain.PriceChangeLog;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.PriceTargetType;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.PriceChangeLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Records cost / selling price movements (FEATURE_2609_28 / PLAN D23).
 *
 * <p><b>용도</b>: 가격 변경 이력({@code price_change_log})을 쓰는 <b>유일한 컴포넌트</b>.
 * <b>필수 규칙</b>: 가격을 바꾸는 코드는 반드시 이 클래스를 거친다.
 * <b>파일</b>: {@code service/price/PriceHistoryRecorder.java}
 *
 * <p>⚠️ A second copy of this logic would drift on the "unchanged → no row" rule and quietly fill
 * the log with noise. There are four call sites and they all come here:
 * <ol>
 *   <li>{@code CostPropagationServiceImpl.updateBasePrice} — {@code PURCHASE_UPDATE}</li>
 *   <li>{@code ProductServiceImpl.updateProduct} — {@code PRODUCT_EDIT}</li>
 *   <li>{@code ListingAssetServiceImpl.recalculateOptionPrices} — {@code PROPAGATION}</li>
 *   <li>{@code ListingOptionServiceImpl.setOptionPrices} — {@code MANUAL}</li>
 * </ol>
 *
 * <p><b>사용 예제</b>:
 * <pre>{@code
 * // ① cost, with the purchase that moved it
 * priceHistoryRecorder.recordProductCost(product, product.getPrice(), newPrice,
 *         PriceChangeReason.PURCHASE_UPDATE, record.getId());
 *
 * // ③ selling price, a whole cell at once (ONE saveAll, never one save per option)
 * priceHistoryRecorder.recordSellingPrices(cell, changes, PriceChangeReason.PROPAGATION);
 * }</pre>
 *
 * <p>🔴 <b>Skip rules — creation is not a movement.</b>
 * <ul>
 *   <li>{@code oldPrice == null} → nothing is written. No previous value means a first setting
 *       (option add), and a row that cannot say what the price came from explains nothing.</li>
 *   <li>{@code DRAFT} cell → nothing is written. A cell that is not on sale yet has no price
 *       history; this is what keeps channel-add and import (which create DRAFT cells) out.</li>
 *   <li>same value → nothing is written, compared with {@code compareTo} so {@code 4000} and
 *       {@code 4000.00} are the same price (⚠️ never {@code equals}).</li>
 * </ul>
 *
 * <p>⚠️ <b>A failure here never breaks the caller.</b> The price has already changed; refusing the
 * change because its history could not be written would be strictly worse. Failures are logged.
 *
 * <p>⚠️ Runs in the caller's transaction (no {@code REQUIRES_NEW}) — the log belongs to the change
 * that produced it, so a rolled-back price change leaves no row behind.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceHistoryRecorder {

    private final PriceChangeLogRepository priceChangeLogRepository;

    /** One selling price movement, before it is filtered. Used by the batch entry point. */
    public record SellingPriceChange(ProductListingOption option, BigDecimal oldPrice, BigDecimal newPrice) {
    }

    /**
     * {@code Product.price} moved.
     *
     * <p>⚠️ No seller axis on purpose: {@code Product.price} is tenant-wide (it has no seller
     * column), so a cost row identifies the product and the person, not a seller. Purchases are
     * per-seller (2609_29) and two sellers buying the same product overwrite each other's base
     * price — {@code purchaseRecordId} is what lets a reader see which purchase won.
     *
     * @param purchaseRecordId the purchase behind the change, or null for a manual edit
     */
    public void recordProductCost(Product product, BigDecimal oldPrice, BigDecimal newPrice,
                                  PriceChangeReason reason, Long purchaseRecordId) {
        if (product == null || !isMovement(oldPrice, newPrice)) {
            return;
        }
        save(List.of(PriceChangeLog.builder()
                .targetType(PriceTargetType.PRODUCT_COST)
                .product(product)
                .oldPrice(oldPrice)
                .newPrice(newPrice)
                .reason(reason)
                .purchaseRecordId(purchaseRecordId)
                .createdBy(currentUsername())
                .build()));
    }

    /**
     * One option's selling price moved. The cell is read from the option — callers that already
     * hold it (and have many options) use {@link #recordSellingPrices} instead.
     */
    public void recordSellingPrice(ProductListingOption option, BigDecimal oldPrice, BigDecimal newPrice,
                                   PriceChangeReason reason) {
        if (option == null) {
            return;
        }
        recordSellingPrices(option.getProductListing(),
                List.of(new SellingPriceChange(option, oldPrice, newPrice)), reason);
    }

    /**
     * A whole cell's selling price movements — <b>one {@code saveAll}</b>. A propagation touches
     * hundreds of options and a per-row save would turn one click into hundreds of INSERT
     * round-trips.
     *
     * @param cell the listing the options belong to; passed in so a DRAFT check does not wake a
     *             LAZY proxy once per option
     */
    public void recordSellingPrices(ProductListing cell, List<SellingPriceChange> changes,
                                    PriceChangeReason reason) {
        if (changes == null || changes.isEmpty() || isDraft(cell)) {
            return;
        }
        List<PriceChangeLog> rows = new ArrayList<>();
        for (SellingPriceChange change : changes) {
            if (change.option() == null || !isMovement(change.oldPrice(), change.newPrice())) {
                continue;
            }
            rows.add(PriceChangeLog.builder()
                    .targetType(PriceTargetType.LISTING_SELLING)
                    .listingOption(change.option())
                    .oldPrice(change.oldPrice())
                    .newPrice(change.newPrice())
                    .reason(reason)
                    .createdBy(currentUsername())
                    .build());
        }
        save(rows);
    }

    /**
     * Is this a movement worth a row? Both values must exist and differ <b>numerically</b>.
     *
     * <p>🔴 {@code compareTo}, not {@code equals}: {@code 4000} and {@code 4000.00} are the same
     * price with different scales, and {@code equals} would log every recalculation as a change.
     */
    private boolean isMovement(BigDecimal oldPrice, BigDecimal newPrice) {
        return oldPrice != null && newPrice != null && oldPrice.compareTo(newPrice) != 0;
    }

    /** A cell that is not on sale yet has no price history (skip rule 2). Null cell = cannot tell → skip. */
    private boolean isDraft(ProductListing cell) {
        return cell == null || cell.getStatus() == ListingStatus.DRAFT;
    }

    /** ⚠️ Never lets a logging failure reach the caller — the price change itself must stand. */
    private void save(List<PriceChangeLog> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            priceChangeLogRepository.saveAll(rows);
        } catch (Exception e) {
            log.warn("[PRICE-HISTORY] failed to record {} row(s): {}", rows.size(), e.getMessage());
        }
    }

    /** Same helper as {@code OrderCancelServiceImpl.currentUsername()} — batch context yields null. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
