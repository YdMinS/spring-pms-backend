package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingMarketImage;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.ListingMarketImageRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ImageStorageService;
import com.pms.service.MarketImageStore;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FEATURE_2611_11 / D4 · D9 · D11 · D21 · D37 · D38 — [「마켓 기준으로 맞추기」] end to end on a Coupang listing (ADMIN
 * success path; 401 · 403 · 404 · 400 are in {@code ListingAssetControllerTest}). The Coupang HTTP client and the photo
 * copy are mocked; everything between them is real.
 */
class ListingMarketSyncControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;
    @Autowired private ListingMarketImageRepository listingMarketImageRepository;

    @MockBean private CoupangApiClient coupangApiClient;
    @MockBean private MarketImageStore marketImageStore;
    @MockBean private ImageStorageService imageStorageService;

    private Long listingId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("쿠팡 본계정").isActive(true).build());
        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("777").name("우리 노출상품명").seller(seller).build());
        listingId = listing.getId();
        productListingOptionRepository.save(ProductListingOption.builder().productListing(listing)
                .optionName("6입").platformOptionId("8123").sellingPrice(new BigDecimal("12900")).active(true).build());
        productListingOptionRepository.save(ProductListingOption.builder().productListing(listing)
                .optionName("12입").sellingPrice(new BigDecimal("22900")).active(true).build());
        generatedProductDataRepository.save(GeneratedProductData.builder().productListing(listing)
                .thumbnailUrl("thumbnails/auto.jpg").thumbnailSource(GeneratedContentSource.AUTO)
                .detailHtml("<p>자동</p>").source(GeneratedContentSource.AUTO).build());

        given(coupangApiClient.get(eq("/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/777"),
                eq(""), any())).willReturn(
                "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductName\":\"마켓 등록상품명\","
                        + "\"displayProductName\":\"마켓 노출상품명\",\"statusName\":\"승인완료\",\"items\":["
                        + "{\"itemName\":\"6입\",\"vendorItemId\":8123,\"salePrice\":12900,"
                        + "\"images\":[{\"imageOrder\":0,\"imageType\":\"REPRESENTATION\",\"cdnPath\":\"https://cdn/six.jpg\"},"
                        + "{\"imageOrder\":1,\"imageType\":\"DETAIL\",\"cdnPath\":\"https://cdn/six-2.jpg\"}],"
                        + "\"contents\":[{\"contentsType\":\"TEXT\",\"contentDetails\":["
                        + "{\"content\":\"<p>마켓 상세</p>\",\"detailType\":\"TEXT\"}]}]},"
                        + "{\"itemName\":\"12입\",\"salePrice\":22900,"
                        + "\"images\":[{\"imageOrder\":0,\"imageType\":\"REPRESENTATION\",\"cdnPath\":\"https://cdn/twelve.jpg\"}]}]}}");
        given(marketImageStore.copy(anyString(), anyLong(), anyInt()))
                .willAnswer(inv -> "thumbnails/copy-" + inv.getArgument(2, Integer.class) + ".jpg");
    }

    @Test
    void marketSync_adminToken_takesNamesDetailAndPhotosPerOption() throws Exception {
        mockMvc.perform(post("/api/admin/product-listings/" + listingId + "/market-sync")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("마켓 노출상품명"))
                .andExpect(jsonPath("$.data.registrationName").value("마켓 등록상품명"))
                .andExpect(jsonPath("$.data.imageNotice").doesNotExist());

        ProductListing listing = productListingRepository.findById(listingId).orElseThrow();
        assertThat(listing.getName()).isEqualTo("마켓 노출상품명");
        assertThat(listing.getRegistrationName()).isEqualTo("마켓 등록상품명");
        GeneratedProductData data = generatedProductDataRepository.findByProductListingId(listingId).orElseThrow();
        assertThat(data.getThumbnailUrl()).isEqualTo("thumbnails/copy-0.jpg");
        assertThat(data.getThumbnailSource()).isEqualTo(GeneratedContentSource.MARKET);
        assertThat(data.getDetailHtml()).isEqualTo("<p>마켓 상세</p>");
        assertThat(data.getSource()).isEqualTo(GeneratedContentSource.MARKET);
        List<ListingMarketImage> rows = listingMarketImageRepository.findByProductListingIdOrderBySortOrderAsc(listingId);
        assertThat(rows).extracting(ListingMarketImage::getRepresentative, ListingMarketImage::getMarketImageType,
                        ListingMarketImage::getImageUrl)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(true, "REPRESENTATION", "thumbnails/copy-0.jpg"),
                        org.assertj.core.groups.Tuple.tuple(false, "DETAIL", "thumbnails/copy-1.jpg"),
                        org.assertj.core.groups.Tuple.tuple(true, "REPRESENTATION", "thumbnails/copy-2.jpg"));
        assertThat(rows.get(0).getProductListingOptionId()).isEqualTo(rows.get(1).getProductListingOptionId());
        assertThat(rows.get(2).getProductListingOptionId()).isNotEqualTo(rows.get(0).getProductListingOptionId());
    }
}
