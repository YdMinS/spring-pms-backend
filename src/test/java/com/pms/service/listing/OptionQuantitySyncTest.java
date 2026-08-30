package com.pms.service.listing;

import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.ProductListingProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The shared line rule extracted from propagation (84): quantities only, matched by productId. Both
 * propagation (3d) and the option-edit re-sync go through this, so the rule is asserted once here.
 */
@ExtendWith(MockitoExtension.class)
class OptionQuantitySyncTest {

    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @InjectMocks private OptionQuantitySync sync;

    private final Product shared = Product.builder().id(99L).build();
    private final Product cellOnly = Product.builder().id(77L).build();
    private final MasterProductOption masterOption = MasterProductOption.builder().id(7L).name("2세트").build();
    private final ProductListingOption cellOption = ProductListingOption.builder().id(5L).optionName("2세트").build();

    private ProductListingProduct line(Long id, Product product, int quantity) {
        return ProductListingProduct.builder().id(id)
                .productListingOption(cellOption).product(product).quantity(quantity).build();
    }

    @Test
    void syncLines_updatesSharedProductQuantity_andLeavesCellOnlyLineAlone() {
        given(masterProductOptionItemRepository.findByOptionId(7L)).willReturn(List.of(
                MasterProductOptionItem.builder().option(masterOption).product(shared).quantity(2).build()));
        given(productListingProductRepository.findByProductListingOptionId(5L))
                .willReturn(List.of(line(3L, shared, 1), line(4L, cellOnly, 5)));

        sync.syncLines(cellOption, masterOption);

        // only the shared line is rewritten (1 → 2); the cell-only line is left as-is (no structure change)
        ArgumentCaptor<ProductListingProduct> captor = ArgumentCaptor.forClass(ProductListingProduct.class);
        verify(productListingProductRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(3L);
        assertThat(captor.getValue().getQuantity()).isEqualTo(2);
    }

    @Test
    void syncLines_unchangedQuantity_savesNothing() {
        given(masterProductOptionItemRepository.findByOptionId(7L)).willReturn(List.of(
                MasterProductOptionItem.builder().option(masterOption).product(shared).quantity(2).build()));
        given(productListingProductRepository.findByProductListingOptionId(5L))
                .willReturn(List.of(line(3L, shared, 2)));

        sync.syncLines(cellOption, masterOption);

        verify(productListingProductRepository, never()).save(any());
    }
}
