package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rule-based (deterministic, NOT an LLM) generator for the market registration name
 * ({@code sellerProductName}) from a master's components and options (FEATURE_2608_06 / 32).
 *
 * <p>The rule branches on the option count → then on the component (BOM) count:</p>
 * <ul>
 *   <li><b>options ≥ 2</b>: list every master component as {@code {brand?} {name}} joined by {@code ", "},
 *       then append {@code " - 옵션확인"} (per-option quantities differ, so they are omitted). Example:
 *       {@code "노브랜드 생수, 다우니 섬유유연제 - 옵션확인"}.</li>
 *   <li><b>options == 1, single component</b>: {@code {brand?} {name} x {quantity}}, e.g.
 *       {@code "노브랜드 생수 x 6"}.</li>
 *   <li><b>options == 1, multi component</b>: each {@code {brand?} {name} x {quantity}} joined by
 *       {@code " + "}, e.g. {@code "노브랜드 생수 x 2 + 다우니 섬유유연제 x 1"}.</li>
 *   <li><b>options == 0</b>: fall back to {@code master.getName()} (defensive; not hit on the normal path).</li>
 * </ul>
 *
 * <p>The registration name is a computed value (never persisted): the master's internal label
 * {@code master.name} stays as-is. This service is pure value assembly (no I/O beyond repository reads).</p>
 */
@Service
@RequiredArgsConstructor
public class RegistrationNameGenerator {

    private final MasterProductOptionRepository optionRepository;
    private final MasterProductOptionItemRepository optionItemRepository;
    private final MasterProductComponentRepository componentRepository;
    private final ProductRepository productRepository;

    /**
     * Build the master-level registration name (34 {@code MasterProductResponse.registrationName}) — branches
     * on the master's <b>full</b> option count (see class doc for the rule).
     */
    public String generate(MasterProduct master) {
        List<MasterProductOption> options = optionRepository.findByMasterProductId(master.getId());
        if (options.size() >= 2) {
            return multiOptionName(master);
        }
        if (options.size() == 1) {
            return singleOptionName(options.get(0));
        }
        return master.getName();
    }

    /**
     * Build the per-channel (listing) registration name (67) — branches on the number of <b>active</b> options
     * of that listing, so the same master yields a different name per channel when option selections differ.
     *
     * <p>⚠️ No repository read here (a matrix loop would N+1): the caller injects {@code masterOptions} (the
     * master options it already batch-loaded). Because every option covers the whole component set (domain
     * invariant, 3b-1), the "≥2" listing = the master's component listing unchanged (no union needed).</p>
     *
     * @param master            the listing's master (non-null; callers guard master==null before calling)
     * @param activeOptionNames the active {@code ProductListingOption.optionName}s of this listing
     * @param masterOptions     the master's options, pre-loaded by the caller (matching key = option name)
     */
    public String generate(MasterProduct master, Collection<String> activeOptionNames,
                           List<MasterProductOption> masterOptions) {
        int n = activeOptionNames.size();
        if (n >= 2) {
            return multiOptionName(master);          // each option covers all components → master listing is exact
        }
        if (n == 1) {
            String only = activeOptionNames.iterator().next();
            MasterProductOption opt = masterOptions.stream()
                    .filter(o -> only.equals(o.getName())).findFirst().orElse(null);
            return opt != null ? singleOptionName(opt) : master.getName();   // defensive: name-match miss
        }
        return master.getName();   // defensive: 0 active options
    }

    /** options ≥ 2 → all master components as "{brand?} {name}" joined by ", " + " - 옵션확인". */
    private String multiOptionName(MasterProduct master) {
        List<MasterProductComponent> components = componentRepository.findByMasterProductId(master.getId());
        List<Long> productIds = components.stream()
                .map(c -> c.getProduct().getId())
                .toList();
        // One query for all component product names/brands (N+1 guard).
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        String joined = productIds.stream()
                .sorted()   // stable productId order
                .map(products::get)
                .map(p -> label(p.getBrand(), p.getProductName()))
                .collect(Collectors.joining(", "));
        return joined + " - 옵션확인";
    }

    /** options == 1 → each item "{brand?} {name} x {quantity}" joined by " + " (single item = no join). */
    private String singleOptionName(MasterProductOption option) {
        List<MasterProductOptionItem> items = optionItemRepository.findByOptionId(option.getId());
        return items.stream()
                .sorted(Comparator.comparing(it -> it.getProduct().getId()))   // stable productId order
                .map(it -> label(it.getProduct().getBrand(), it.getProduct().getProductName())
                        + " x " + it.getQuantity())
                .collect(Collectors.joining(" + "));
    }

    /** Blank brand → name only (no leading space, e.g. "생수 x 6"); else "{brand} {name}". */
    private String label(String brand, String name) {
        return (brand == null || brand.isBlank()) ? name : brand + " " + name;
    }
}
