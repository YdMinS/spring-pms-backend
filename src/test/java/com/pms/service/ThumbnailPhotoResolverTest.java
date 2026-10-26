package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailPhoto;
import com.pms.repository.ThumbnailPhotoRepository;
import com.pms.service.collage.CollageStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** Stage order of the thumbnail photo (FEATURE_2610_32 D4): listing ?? seller ?? master. */
@ExtendWith(MockitoExtension.class)
class ThumbnailPhotoResolverTest {

    @Mock private ThumbnailPhotoRepository thumbnailPhotoRepository;
    @InjectMocks private ThumbnailPhotoResolver resolver;

    private static final MasterProduct MASTER = MasterProduct.builder().id(1L).name("m").build();

    private static ThumbnailPhoto row(String scopeKey, long imageId) {
        return ThumbnailPhoto.builder().masterProduct(MASTER).scopeKey(scopeKey)
                .image(MasterProductImage.builder().id(imageId).masterProduct(MASTER).build()).build();
    }

    private static ProductListing listing() {
        return ProductListing.builder().id(42L).masterProduct(MASTER)
                .seller(Seller.builder().id(7L).sellerName("s").build()).build();
    }

    @Test
    void chain_listingStage_listingThenSellerThenMaster() {
        assertThat(ThumbnailPhotoResolver.chain(CollageStage.LISTING, 7L, 42L))
                .containsExactly("LISTING:42", "SELLER:7", "MASTER");
        assertThat(ThumbnailPhotoResolver.chain(CollageStage.SELLER, 7L, null)).containsExactly("SELLER:7", "MASTER");
        assertThat(ThumbnailPhotoResolver.chain(CollageStage.MASTER, null, null)).containsExactly("MASTER");
    }

    @Test
    void resolve_sellerRowWinsOverMasterRow() {
        given(thumbnailPhotoRepository.findByMasterProduct_Id(1L))
                .willReturn(List.of(row("MASTER", 10L), row("SELLER:7", 11L)));

        assertThat(resolver.resolve(listing())).get().extracting(MasterProductImage::getId).isEqualTo(11L);
    }

    @Test
    void resolve_listingRowWins() {
        given(thumbnailPhotoRepository.findByMasterProduct_Id(1L))
                .willReturn(List.of(row("MASTER", 10L), row("SELLER:7", 11L), row("LISTING:42", 12L)));

        assertThat(resolver.resolve(listing())).get().extracting(MasterProductImage::getId).isEqualTo(12L);
    }

    @Test
    void resolve_noRow_isEmpty() {
        given(thumbnailPhotoRepository.findByMasterProduct_Id(1L)).willReturn(List.of());

        assertThat(resolver.resolve(listing())).isEmpty();
    }
}
