package com.pms.service;

import com.pms.domain.MarginPolicy;
import com.pms.domain.Package;
import com.pms.domain.ProductListing;
import com.pms.repository.MarginPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Selling-price engine (margin reverse-calc) — FEATURE_2608_06 / 3b-2.
 *
 * <p>Per option, given the option's cost sum (Σ product.price × quantity) and its parent cell:</p>
 * <pre>
 *   sellingPrice = (costSum + delivery + box) / (1 − commissionRate − marginRate)
 * </pre>
 * where commissionRate = {@code CommissionRateService.findRate(platform, categoryId)} (always returns a
 * fallback — never null), delivery = {@code cell.delivery.cost}, box = {@code cell.package.cost}, and
 * marginRate = {@code MarginPolicy(seller, platform).marginRate}. The result is rounded to the nearest
 * 10 won ({@code setScale(-1, HALF_UP)}).
 *
 * <p>400 ({@link IllegalArgumentException}) when: delivery unset, box unset, margin preset missing, or
 * the denominator {@code (1 − commission − margin) ≤ 0} (commission + margin ≥ 100%).</p>
 */
@Service
@RequiredArgsConstructor
public class PriceCalculator {

    private final CommissionRateService commissionRateService;
    private final MarginPolicyRepository marginPolicyRepository;

    /**
     * Compute the rounded selling price for one option of {@code cell}.
     *
     * @param cell    the channel listing (source of platform, category, delivery, box, seller, margin)
     * @param costSum Σ(product.price × quantity) for that option's BOM
     * @return selling price rounded to the nearest 10 won
     * @throws IllegalArgumentException (→400) on missing delivery/box/margin or denominator ≤ 0
     */
    public BigDecimal calculatePrice(ProductListing cell, BigDecimal costSum) {
        BigDecimal commissionRate = commissionRateService.findRate(
                cell.getPlatform(), cell.getCategory() == null ? null : cell.getCategory().getId());

        if (cell.getDelivery() == null || cell.getDelivery().getCost() == null) {
            throw new IllegalArgumentException("배송 미설정");
        }
        BigDecimal delivery = cell.getDelivery().getCost();

        Package box = cell.getPackage_();
        if (box == null || box.getCost() == null) {
            throw new IllegalArgumentException("박스 미설정");
        }

        MarginPolicy margin = marginPolicyRepository
                .findBySellerIdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException("마진 프리셋 없음"));

        BigDecimal denominator = BigDecimal.ONE.subtract(commissionRate).subtract(margin.getMarginRate());
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("수수료+마진이 100% 이상");
        }

        BigDecimal numerator = costSum.add(delivery).add(box.getCost());
        // Divide with headroom, then round to the nearest 10 won; normalize scale to 2 for the DECIMAL(10,2) column.
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP)
                .setScale(-1, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
