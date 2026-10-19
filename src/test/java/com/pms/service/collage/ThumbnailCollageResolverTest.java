package com.pms.service.collage;

import com.pms.domain.CollageLayout;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailCollage;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.ThumbnailCollageRepository;
import com.pms.service.MasterProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Stage order listing ?? seller ?? master (D37), a switched-off stage (D42) and the 2-component rule (D3).
 */
@ExtendWith(MockitoExtension.class)
class ThumbnailCollageResolverTest {

    @Mock private ThumbnailCollageRepository thumbnailCollageRepository;
    @Mock private MasterProductComponentRepository masterProductComponentRepository;
    @Mock private MasterProductService masterProductService;
    @InjectMocks private ThumbnailCollageResolver resolver;

    private final MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
    private final ProductListing cell = ProductListing.builder().id(100L).masterProduct(master)
            .seller(Seller.builder().id(7L).sellerName("행복상회").build()).build();

    /** {@code masterProductService} is a @Lazy field (bean cycle) — @InjectMocks stops at the constructor. */
    @BeforeEach
    void injectLazyService() {
        ReflectionTestUtils.setField(resolver, "masterProductService", masterProductService);
    }

    private static ThumbnailCollage row(String scopeKey, boolean enabled, CollageLayout layout) {
        return ThumbnailCollage.builder().scopeKey(scopeKey).enabled(enabled).layout(layout).build();
    }

    private void twoComponents() {
        given(masterProductService.isBundle(1L)).willReturn(true);
        given(masterProductComponentRepository.findWithProductByMasterProductIdIn(List.of(1L))).willReturn(List.of(
                MasterProductComponent.builder().product(Product.builder().id(20L).build()).build(),
                MasterProductComponent.builder().product(Product.builder().id(10L).build()).build()));
    }

    @Test
    void resolve_sellerStageWinsOverMaster() {
        CollageLayout master = CollageLayouts.initial(List.of(10L, 20L));
        CollageLayout seller = CollageLayouts.initial(List.of(20L, 10L));
        given(thumbnailCollageRepository.findByMasterProduct_Id(1L)).willReturn(List.of(
                row(CollageScopes.MASTER, true, master), row(CollageScopes.seller(7L), true, seller)));
        twoComponents();

        ThumbnailCollageResolver.ResolvedCollage resolved = resolver.resolve(cell).orElseThrow();

        assertThat(resolved.layout()).isSameAs(seller);
        assertThat(resolved.products()).extracting(Product::getId).containsExactly(10L, 20L);
    }

    @Test
    void resolve_listingStageSwitchedOff_isEmptyEvenWithMasterCollage() {
        given(thumbnailCollageRepository.findByMasterProduct_Id(1L)).willReturn(List.of(
                row(CollageScopes.MASTER, true, CollageLayouts.initial(List.of(10L, 20L))),
                row(CollageScopes.listing(100L), false, null)));

        assertThat(resolver.resolve(cell)).isEmpty();
    }

    @Test
    void resolve_singleComponentMaster_isEmpty() {
        given(thumbnailCollageRepository.findByMasterProduct_Id(1L)).willReturn(List.of(
                row(CollageScopes.MASTER, true, CollageLayouts.initial(List.of(10L, 20L)))));
        given(masterProductService.isBundle(1L)).willReturn(false);

        assertThat(resolver.resolve(cell)).isEmpty();
    }
}
