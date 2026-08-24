package com.pms.service;

import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.repository.MarginPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Selling-price engine (margin reverse-calc) — FEATURE_2608_06 / 3b-2, rewired in / 13.
 *
 * <p>Per option, given the option's cost sum (Σ product.price × quantity), its parent cell and its matched
 * master option:</p>
 * <pre>
 *   sellingPrice = (costSum + delivery + box) / (1 − commissionRate − marginRate)
 * </pre>
 * where commissionRate = {@code CommissionRateService.findRate(platform, master-category id)} (always
 * returns a fallback — never null), delivery = {@code resolveDelivery(cell, option).cost}, box =
 * {@code resolvePackage(cell, option).cost} (both from {@link MasterChannelConfigService} = option override
 * ?? master default), and marginRate = {@code MarginPolicy(seller, platform).marginRate}. The result is
 * rounded to the nearest 10 won ({@code setScale(-1, HALF_UP)}).
 *
 * <p>400 ({@link IllegalArgumentException}) when: category/delivery/box unset (raised by the resolver),
 * margin preset missing, or the denominator {@code (1 − commission − margin) ≤ 0}.</p>
 */
@Service
@RequiredArgsConstructor
public class PriceCalculator {

    private final CommissionRateService commissionRateService;
    private final MarginPolicyRepository marginPolicyRepository;
    private final MasterChannelConfigService masterChannelConfigService;

    /**
     * Compute the rounded selling price for one option of {@code cell}.
     *
     * @param cell         the channel listing (source of platform, seller, margin, master link)
     * @param masterOption the matched master option (delivery/box overrides); null = master defaults
     * @param costSum      Σ(product.price × quantity) for that option's BOM
     * @return selling price rounded to the nearest 10 won
     * @throws IllegalArgumentException (→400) on missing category/delivery/box/margin or denominator ≤ 0
     */
    public BigDecimal calculatePrice(ProductListing cell, MasterProductOption masterOption, BigDecimal costSum) {
        // Category, delivery and box now come from the master/option (the resolver owns the null checks).
        BigDecimal commissionRate = commissionRateService.findRate(
                cell.getPlatform(), masterChannelConfigService.resolveStandardCategory(cell).getId());

        BigDecimal delivery = masterChannelConfigService.resolveDelivery(cell, masterOption).getCost();
        BigDecimal box = masterChannelConfigService.resolvePackage(cell, masterOption).getCost();

        MarginPolicy margin = marginPolicyRepository
                .findBySellerIdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException("마진 프리셋 없음"));

        BigDecimal denominator = BigDecimal.ONE.subtract(commissionRate).subtract(margin.getMarginRate());
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("수수료+마진이 100% 이상");
        }

        BigDecimal numerator = costSum.add(delivery).add(box);
        // Divide with headroom, then round to the nearest 10 won; normalize scale to 2 for the DECIMAL(10,2) column.
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP)
                .setScale(-1, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
