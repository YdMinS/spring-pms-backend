package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.dto.response.PropagateResponse;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Layer A (FEATURE_2608_06 / 3d): propagate re-generates linked cells that already have assets, dirty-marks
 * on-market cells, skips ungenerated / DRAFT cells, and isolates a per-cell failure.
 *
 * <p>⚠️ The {@code self} proxy is wired to the real service instance (see setUp), so {@code propagateOne} runs
 * its real logic — a unit test cannot verify {@code REQUIRES_NEW} itself, only the loop's try/catch tally
 * (the REQUIRES_NEW wiring is confirmed by code review + integration no-op).</p>
 */
@ExtendWith(MockitoExtension.class)
class MasterPropagationServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private ListingAssetService listingAssetService;
    @InjectMocks private MasterPropagationServiceImpl service;

    private static final Long MASTER_ID = 10L;

    @BeforeEach
    void wireSelfProxy() {
        // In production `self` is the @Lazy proxy (REQUIRES_NEW); in the unit test point it at the real
        // instance so propagateOne runs its real logic against the mocks.
        ReflectionTestUtils.setField(service, "self", service);
        // Lenient: the 404 test overrides with an absent id and does not use this stub.
        lenient().when(masterProductRepository.findScopedById(MASTER_ID))
                .thenReturn(Optional.of(MasterProduct.builder().id(MASTER_ID).name("마스터").build()));
    }

    private ProductListing cell(Long id, String platformProductId) {
        return ProductListing.builder().id(id).platform("COUPANG").name("셀-" + id)
                .platformProductId(platformProductId).build();   // masterProduct null → quantity sync no-op
    }

    private void hasGenerated(Long cellId) {
        given(generatedProductDataRepository.findByProductListingId(cellId))
                .willReturn(Optional.of(GeneratedProductData.builder().thumbnailUrl("t").detailHtml("d").build()));
    }

    // (a) happy: 2 on-market cells with assets → each regenerated once + needsMarketSync=true saved.
    @Test
    void propagate_onMarketCellsWithAssets_regeneratesAndDirtyMarks() {
        ProductListing c1 = cell(1L, "SP-1");
        ProductListing c2 = cell(2L, "SP-2");
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(c1, c2));
        hasGenerated(1L);
        hasGenerated(2L);
        // Cells have null master → quantity sync is a no-op (no option/BOM repo calls).

        PropagateResponse response = service.propagate(MASTER_ID);

        assertThat(response.getPropagated()).isEqualTo(2);
        assertThat(response.getSkipped()).isZero();
        assertThat(response.getFailed()).isZero();
        verify(listingAssetService).regenerateAssets(c1);
        verify(listingAssetService).regenerateAssets(c2);

        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(ProductListing::isNeedsMarketSync);
    }

    // (b) ungenerated cell = skipped (no regenerate); DRAFT cell (platformProductId null) = regenerated but
    //     NOT dirty-marked (needsMarketSync stays false → no save).
    @Test
    void propagate_skipsUngenerated_draftRegeneratedButNotMarked() {
        ProductListing ungenerated = cell(1L, "SP-1");
        ProductListing draft = cell(2L, null);
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(ungenerated, draft));
        given(generatedProductDataRepository.findByProductListingId(1L)).willReturn(Optional.empty());
        hasGenerated(2L);
        // DRAFT cell has null master → quantity sync is a no-op.

        PropagateResponse response = service.propagate(MASTER_ID);

        assertThat(response.getSkipped()).isEqualTo(1);
        assertThat(response.getPropagated()).isEqualTo(1);
        verify(listingAssetService, never()).regenerateAssets(ungenerated);
        verify(listingAssetService).regenerateAssets(draft);
        verify(productListingRepository, never()).save(any());   // DRAFT not marked → no save
    }

    // (c) isolation: cell2 propagateOne throws (regenerate fails) → cell1 fully processed, failed=1, not aborted.
    @Test
    void propagate_isolatesPerCellFailure() {
        ProductListing ok = cell(1L, "SP-1");
        ProductListing boom = cell(2L, "SP-2");
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(ok, boom));
        hasGenerated(1L);
        hasGenerated(2L);
        // Cells have null master → quantity sync is a no-op. Lenient so the ok cell's non-matching call to the
        // same mocked method does not trip strict-stubbing (which propagate's try/catch would count as failed).
        lenient().when(listingAssetService.regenerateAssets(boom)).thenThrow(new RuntimeException("render 500"));

        PropagateResponse response = service.propagate(MASTER_ID);

        assertThat(response.getPropagated()).isEqualTo(1);
        assertThat(response.getFailed()).isEqualTo(1);
        verify(listingAssetService).regenerateAssets(ok);
        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());   // only the ok cell was dirty-marked
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().isNeedsMarketSync()).isTrue();
    }

    // matched-option quantity sync: master option qty change → cell BOM line qty updated for the shared product.
    @Test
    void propagate_syncsMatchedOptionBomQuantity() {
        Product product = Product.builder().id(99L).build();
        MasterProduct master = MasterProduct.builder().id(MASTER_ID).name("마스터").build();
        ProductListing cell = ProductListing.builder().id(1L).platform("COUPANG").name("셀")
                .platformProductId("SP-1").masterProduct(master).build();
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        hasGenerated(1L);

        ProductListingOption cellOption = ProductListingOption.builder().id(5L).optionName("2세트").build();
        given(productListingOptionRepository.findByProductListingId(1L)).willReturn(List.of(cellOption));

        MasterProductOption masterOption = MasterProductOption.builder().id(7L).name("2세트")
                .masterProduct(master).build();
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(masterOption));
        given(masterProductOptionItemRepository.findByOptionId(7L)).willReturn(List.of(
                MasterProductOptionItem.builder().option(masterOption).product(product).quantity(2).build()));

        ProductListingProduct bomLine = ProductListingProduct.builder().id(3L)
                .productListingOption(cellOption).product(product).quantity(1).build();   // old qty 1
        given(productListingProductRepository.findByProductListingOptionId(5L)).willReturn(List.of(bomLine));

        service.propagate(MASTER_ID);

        ArgumentCaptor<ProductListingProduct> captor = ArgumentCaptor.forClass(ProductListingProduct.class);
        verify(productListingProductRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualTo(2);   // synced from master (1 → 2)
    }

    // 404: cross-tenant/absent master → ResourceNotFoundException (findScopedById empty).
    @Test
    void propagate_absentMaster_throwsNotFound() {
        given(masterProductRepository.findScopedById(eq(404L))).willReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.propagate(404L))
                .isInstanceOf(com.pms.exception.ResourceNotFoundException.class);
    }
}
