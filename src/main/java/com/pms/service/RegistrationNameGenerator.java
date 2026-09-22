package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductRepository;
import com.pms.service.listing.CellBomResolver;
import com.pms.service.listing.OptionCheckSuffix;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
    /** 셀 옵션의 구성품은 마스터를 타고 얻는다(2609_71) — 셀 BOM 사본을 읽지 않는다. */
    private final CellBomResolver cellBomResolver;

    /**
     * Build the master-level registration name (34 {@code MasterProductResponse.registrationName}) — branches
     * on the master's <b>full</b> option count (see class doc for the rule).
     *
     * @param suffix the resolved "옵션확인" suffix config (69), applied only on the options ≥ 2 path
     */
    public String generate(MasterProduct master, OptionCheckSuffix suffix) {
        List<MasterProductOption> options = optionRepository.findByMasterProductId(master.getId());
        if (options.size() >= 2) {
            return multiOptionName(master, suffix);
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
     * <p>2609_22/D1: the single option is resolved through {@code master_product_option_id}, never the name —
     * a channel may rename its options. D7: an option that has no master option behind it (channel-only, D2)
     * builds the same shape from the <b>cell's own BOM</b> instead of falling back to {@code master.name}.</p>
     *
     * <p>⚠️ Only the {@code n == 1} branch reads a repository (one query, as before); {@code n >= 2} reads the
     * master components exactly like the master-level overload. Callers must pass the options they already
     * loaded — never load them here (a matrix loop would N+1).</p>
     *
     * @param master        the listing's master (non-null; callers guard master==null before calling)
     * @param activeOptions the <b>active</b> options of this listing (already filtered by the caller)
     * @param suffix        the resolved "옵션확인" suffix config (69), applied only on the ≥ 2 branch
     */
    public String generate(MasterProduct master, List<ProductListingOption> activeOptions,
                           OptionCheckSuffix suffix) {
        int n = activeOptions.size();
        if (n >= 2) {
            return multiOptionName(master, suffix);  // each option covers all components → master listing is exact
        }
        if (n == 1) {
            ProductListingOption only = activeOptions.get(0);
            // ⚠️ id only — safe on a LAZY proxy (no extra query).
            MasterProductOption masterOption = only.getMasterProductOption();
            return masterOption != null ? singleOptionName(masterOption) : cellOptionName(master, only);
        }
        return master.getName();   // defensive: 0 active options
    }

    /**
     * options ≥ 2 → all master components as "{brand?} {name}" joined by ", ", then the "옵션확인" suffix per the
     * resolved config (69): {@code enabled} appends {@code " - {text}"}, disabled omits it (per-option quantities
     * differ, so they are never listed here regardless).
     */
    private String multiOptionName(MasterProduct master, OptionCheckSuffix suffix) {
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
        return suffix.enabled() ? joined + " - " + suffix.text() : joined;
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

    /**
     * 2609_22/D7: same shape as {@link #singleOptionName}, for a cell option the master does not own.
     *
     * <p>⚠️ 2609_71 이후 이 경로는 사실상 마스터 이름 폴백이다 — 호출 조건이 「마스터 옵션 없음」이고,
     * 구성품의 유일한 출처가 마스터가 됐으므로 채널 전용 옵션은 구성품을 알 수 없다(미매핑). 폴백을
     * 남겨 두는 이유는 조용히 빈 이름을 만들지 않기 위해서다.
     */
    private String cellOptionName(MasterProduct master, ProductListingOption cellOption) {
        // 2609_71: 구성품은 마스터를 타고 읽는다. 채널 전용 옵션(미매핑)·빈 BOM → 마스터 이름으로 폴백.
        List<CellBomResolver.Line> lines = cellBomResolver.forOption(cellOption).lines();
        if (lines.isEmpty()) {
            return master.getName();
        }
        return lines.stream()   // 물품 id 오름차순은 resolver 가 이미 보장한다
                .map(line -> label(line.product().getBrand(), line.productName())
                        + " x " + line.quantity())
                .collect(Collectors.joining(" + "));
    }

    /** Blank brand → name only (no leading space, e.g. "생수 x 6"); else "{brand} {name}". */
    private String label(String brand, String name) {
        return (brand == null || brand.isBlank()) ? name : brand + " " + name;
    }
}
