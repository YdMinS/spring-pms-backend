package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingMarketImage;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.MarketSyncResponse;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.ListingMarketImageRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ImageStorageService;
import com.pms.service.MarketImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2611_11 / D2 · D4 · D8 · D9 · D11 · D19 · D21 · D35 · D36 · D37 · D38 — 「마켓 기준으로 맞추기」. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListingMarketSyncServiceTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private ListingMarketImageRepository marketImageRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private MarketImageStore marketImageStore;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ListingChannel adapter;

    @InjectMocks private ListingMarketSyncServiceImpl service;

    private static final Long CELL_ID = 100L;
    private final Seller seller = Seller.builder().id(7L).build();
    private final MarketplaceAccount account = MarketplaceAccount.builder().id(3L).isActive(true).build();
    private final ProductListingOption first = ProductListingOption.builder().id(11L).optionName("1개")
            .platformOptionId("V1").active(true).build();
    private final ProductListingOption second = ProductListingOption.builder().id(12L).optionName("2개")
            .active(true).build();

    private ProductListing cell(Platform platform) {
        return ProductListing.builder().id(CELL_ID).platform(platform).name("우리 이름")
                .registrationName("직접 쓴 등록상품명").platformProductId("P-1").seller(seller).build();
    }

    @BeforeEach
    void setUp() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(eq(7L), any())).willReturn(Optional.of(account));
        given(resolver.resolve(any())).willReturn(adapter);
        given(productListingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(generatedProductDataRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(first, second));
        given(marketImageStore.copy(any(), anyLong(), anyInt()))
                .willAnswer(inv -> "thumbnails/copy-of-" + inv.getArgument(0, String.class).replace("https://m/", ""));
    }

    private static ImportedProduct coupang(String detail, List<ImportedProduct.MarketImage> images) {
        return new ImportedProduct("마켓 등록상품명", null, null, null, List.of(), null, List.of(), List.of(),
                List.of(), null, "마켓 노출상품명", null, detail, images, null);
    }

    /** D4 · D8 · D11 · D21 · D36 · D37 · D38: Coupang — every value replaced, photos per option, thumbnail = first option's. */
    @Test
    void syncCoupang_replacesNamesDetailAndPhotos_thumbnailIsFirstOptionMainPhoto() {
        ProductListing cell = cell(Platform.COUPANG);
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        GeneratedProductData existing = GeneratedProductData.builder().id(5L).productListing(cell)
                .thumbnailUrl("thumbnails/auto.jpg").thumbnailSource(GeneratedContentSource.MANUAL_OVERRIDE)
                .detailHtml("<p>직접</p>").source(GeneratedContentSource.MANUAL_OVERRIDE).build();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.of(existing));
        given(marketImageRepository.findByProductListingIdOrderBySortOrderAsc(CELL_ID)).willReturn(List.of(
                ListingMarketImage.builder().imageUrl("thumbnails/old.jpg").build()));
        given(adapter.fetchProduct("P-1", account)).willReturn(coupang("<p>마켓 상세</p>", List.of(
                new ImportedProduct.MarketImage(0, "V1", "1개", true, "REPRESENTATION", "https://m/a.jpg"),
                new ImportedProduct.MarketImage(0, "V1", "1개", false, "DETAIL", "https://m/b.jpg"),
                new ImportedProduct.MarketImage(1, null, "2개", true, "REPRESENTATION", "https://m/c.jpg"),
                new ImportedProduct.MarketImage(2, "V9", "없는 옵션", true, "REPRESENTATION", "https://m/z.jpg"))));

        MarketSyncResponse res = service.syncFromMarket(CELL_ID);

        assertThat(res.name()).isEqualTo("마켓 노출상품명");
        assertThat(res.registrationName()).isEqualTo("마켓 등록상품명");
        assertThat(res.imageNotice()).isNull();
        ArgumentCaptor<GeneratedProductData> data = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(data.capture());
        assertThat(data.getValue().getThumbnailUrl()).isEqualTo("thumbnails/copy-of-a.jpg");
        assertThat(data.getValue().getThumbnailSource()).isEqualTo(GeneratedContentSource.MARKET);
        assertThat(data.getValue().getDetailHtml()).isEqualTo("<p>마켓 상세</p>");
        assertThat(data.getValue().getSource()).isEqualTo(GeneratedContentSource.MARKET);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ListingMarketImage>> rows = ArgumentCaptor.forClass(List.class);
        verify(marketImageRepository).deleteByProductListingId(CELL_ID);
        verify(marketImageRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(ListingMarketImage::getProductListingOptionId,
                        ListingMarketImage::getRepresentative, ListingMarketImage::getMarketImageType,
                        ListingMarketImage::getImageUrl)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(11L, true, "REPRESENTATION", "thumbnails/copy-of-a.jpg"),
                        org.assertj.core.groups.Tuple.tuple(11L, false, "DETAIL", "thumbnails/copy-of-b.jpg"),
                        org.assertj.core.groups.Tuple.tuple(12L, true, "REPRESENTATION", "thumbnails/copy-of-c.jpg"));
        // The photos of a market option this listing does not have are not copied.
        verify(marketImageStore, never()).copy(eq("https://m/z.jpg"), anyLong(), anyInt());
        // The replaced copies are dropped (best effort).
        verify(imageStorageService).deleteImage("thumbnails/old.jpg");
        verify(imageStorageService).deleteImage("thumbnails/auto.jpg");
    }

    /** D19 · D35: 11st — one name, no 「등록상품명」; a failed photo read keeps the photos and returns the guidance. */
    @Test
    void syncElevenst_photoReadFailed_namesAndDetailTaken_photosKept_noticeReturned() {
        ProductListing cell = cell(Platform.ELEVENST);
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.empty());
        given(adapter.fetchProduct("P-1", account)).willReturn(new ImportedProduct(null, null, null, null, List.of(),
                null, List.of(), List.of(), List.of(), null, "11번가 상품명", null, "<p>11번가 상세</p>", List.of(),
                ElevenstListingAdapter.PHOTO_READ_FAILED));

        MarketSyncResponse res = service.syncFromMarket(CELL_ID);

        assertThat(res.name()).isEqualTo("11번가 상품명");
        assertThat(res.registrationName()).isNull();
        assertThat(res.imageNotice()).isEqualTo("11번가 사진을 가져오지 못했습니다");
        verify(marketImageStore, never()).copy(any(), anyLong(), anyInt());
        verify(marketImageRepository, never()).deleteByProductListingId(anyLong());
        ArgumentCaptor<GeneratedProductData> data = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(data.capture());
        assertThat(data.getValue().getDetailHtml()).isEqualTo("<p>11번가 상세</p>");
        assertThat(data.getValue().getSource()).isEqualTo(GeneratedContentSource.MARKET);
        assertThat(data.getValue().getThumbnailSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    /** R: a failed copy keeps the stored photos and thumbnail, drops the copies made so far and says so. */
    @Test
    void syncCopyFailed_photosAndThumbnailKept_noticeReturned() {
        ProductListing cell = cell(Platform.ESM);
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        GeneratedProductData existing = GeneratedProductData.builder().id(5L).productListing(cell)
                .thumbnailUrl("thumbnails/auto.jpg").thumbnailSource(GeneratedContentSource.AUTO)
                .detailHtml("<p>자동</p>").source(GeneratedContentSource.AUTO).build();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.of(existing));
        org.mockito.BDDMockito.willThrow(new IllegalArgumentException("마켓 사진이 이미지 파일이 아닙니다"))
                .given(marketImageStore).copy(eq("https://m/b.jpg"), anyLong(), anyInt());
        given(adapter.fetchProduct("P-1", account)).willReturn(new ImportedProduct(null, null, null, null, List.of(),
                null, List.of(), List.of(), List.of(), null, "ESM 상품명", null, null, List.of(
                        new ImportedProduct.MarketImage(null, null, null, true, null, "https://m/a.jpg"),
                        new ImportedProduct.MarketImage(null, null, null, false, null, "https://m/b.jpg")), null));

        MarketSyncResponse res = service.syncFromMarket(CELL_ID);

        assertThat(res.imageNotice()).isEqualTo("마켓 사진을 내려받지 못했습니다");
        verify(marketImageRepository, never()).deleteByProductListingId(anyLong());
        verify(imageStorageService).deleteImage("thumbnails/copy-of-a.jpg");
        verify(imageStorageService, never()).deleteImage("thumbnails/auto.jpg");
        // Nothing of the assets row changed (no detail from the market, photos kept) → no save.
        verify(generatedProductDataRepository, never()).save(any());
    }

    /** D9: a listing that is not on a market cannot be synced. */
    @Test
    void syncNotOnMarket_is400() {
        ProductListing draft = cell(Platform.COUPANG).toBuilder().platformProductId(null).build();
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.syncFromMarket(CELL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("마켓에 올라가지 않은 판매상품입니다");
        verify(adapter, never()).fetchProduct(any(), any());
    }
}
