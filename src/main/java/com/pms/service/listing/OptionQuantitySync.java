package com.pms.service.listing;

import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.ProductListingProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Line-level BOM quantity sync for ONE (cell option × master option) pair — the shared rule behind both
 * 3d propagation ({@code MasterPropagationServiceImpl.syncOptionQuantities}) and the narrow price re-sync
 * that follows a master option edit (FEATURE_2608_06 / 84).
 *
 * <p><b>Quantities only — never structure.</b> Lines are matched by {@code productId}; the quantity is
 * updated only where both sides carry that product. A cell-only line is left as-is and a master-only
 * product is skipped (adding a BOM line would be a structure change, out of scope).</p>
 *
 * <p>⚠️ Both call sites MUST use this component rather than re-implementing the rule — a second copy would
 * drift from propagation and silently produce different channel prices.</p>
 */
@Component
@RequiredArgsConstructor
public class OptionQuantitySync {

    private final MasterProductOptionItemRepository masterProductOptionItemRepository;
    private final ProductListingProductRepository productListingProductRepository;

    /**
     * Copy the master option's quantity vector onto the matched cell option's BOM lines.
     *
     * @param cellOption   the channel cell's option (already matched by {@code optionName} by the caller)
     * @param masterOption the master option whose quantities are authoritative
     */
    public void syncLines(ProductListingOption cellOption, MasterProductOption masterOption) {
        Map<Long, Integer> masterQtyByProduct = masterProductOptionItemRepository
                .findByOptionId(masterOption.getId()).stream()
                .collect(Collectors.toMap(
                        it -> it.getProduct().getId(), MasterProductOptionItem::getQuantity, (first, dup) -> first));

        for (ProductListingProduct line : productListingProductRepository
                .findByProductListingOptionId(cellOption.getId())) {
            Integer newQuantity = masterQtyByProduct.get(line.getProduct().getId());
            if (newQuantity != null && !newQuantity.equals(line.getQuantity())) {
                productListingProductRepository.save(line.toBuilder().quantity(newQuantity).build());
            }
        }
    }
}
