package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이미 연결된 셀의 마켓 이미지 조회(온보딩, 2026-09-19): 권한(401/403) + happy path(두 목록 분리·순서 보존)
 * + 이미지 없음(빈 목록) + 400 두 경우 + 다른 테넌트 404.
 *
 * <p>{@link CoupangApiClient} 는 목이라 라이브 호출이 없다. 셀은 <b>마스터에 연결된</b> 상태로 심는다 —
 * 가져오기 미리보기가 {@code existsByPlatformProductId} 로 막는 바로 그 상태가 여기서는 통과해야 한다.</p>
 */
class ListingChannelImageControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private CoupangAccountCredentialRepository credentialRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private CoupangApiClient coupangApiClient;

    /**
     * 썸네일 2장 + 상세 2장. 호스트 없는 경로(썸네일 2번째)와 HTML 안의 {@code img src}(상세 2번째)를
     * 섞어 둔 것은 어댑터의 정규화·파싱을 그대로 타는지 보기 위해서다.
     */
    private static final String COUPANG_PRODUCT_WITH_IMAGES =
            "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":222333444,"
                    + "\"sellerProductName\":\"노브랜드 생수 2L 6입\",\"displayCategoryCode\":\"63955\","
                    + "\"statusName\":\"승인완료\",\"items\":[{"
                    + "\"itemName\":\"6입\",\"vendorItemId\":8123456789,\"sellerProductItemId\":9123,"
                    + "\"salePrice\":5900,\"originalPrice\":5900,\"maximumBuyCount\":50,"
                    + "\"images\":[{\"cdnPath\":\"https://img1.coupangcdn.com/thumb-a.jpg\"},"
                    + "{\"cdnPath\":\"vendor_inventory/0e21/thumb-b.jpg\"}],"
                    + "\"contents\":[{\"contentDetails\":["
                    + "{\"detailType\":\"IMAGE\",\"content\":\"https://img1.coupangcdn.com/detail-1.jpg\"},"
                    + "{\"detailType\":\"TEXT\",\"content\":"
                    + "\"<div><img src='https://img1.coupangcdn.com/detail-2.jpg'/></div>\"}]}]}]}}";

    /** 같은 상품이되 이미지 키가 통째로 없는 응답 — 빈 목록이어야 하고, 예외가 되어서는 안 된다. */
    private static final String COUPANG_PRODUCT_NO_IMAGES =
            "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":222333444,"
                    + "\"sellerProductName\":\"노브랜드 생수 2L 6입\",\"displayCategoryCode\":\"63955\","
                    + "\"statusName\":\"승인완료\",\"items\":[{"
                    + "\"itemName\":\"6입\",\"vendorItemId\":8123456789,\"sellerProductItemId\":9123,"
                    + "\"salePrice\":5900,\"originalPrice\":5900,\"maximumBuyCount\":50}]}}";

    private Seller seller;
    private Long listingId;

    @BeforeEach
    void seed() {
        seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());

        MarketplaceAccountFixture.saveCredential(credentialRepository,
                marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder()
                        .seller(seller).platform(Platform.COUPANG).accountAlias("메인")
                        .isActive(true).build()),
                "V1", "wing-user");

        // 🔴 마스터에 연결된 셀 = 가져오기 미리보기가 400 으로 막는 상태. 이 경로는 통과해야 한다.
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("노브랜드 생수 2L").active(true).build());
        listingId = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("222333444").name("노브랜드 생수 2L")
                .status(ListingStatus.SELLING).seller(seller).masterProduct(master).build()).getId();

        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(COUPANG_PRODUCT_WITH_IMAGES);
    }

    private String path(Long id) {
        return "/api/admin/product-listings/" + id + "/channel-images";
    }

    // ---- happy path ----

    @Test
    void testImages200ReturnsThumbnailAndDetailSeparately() throws Exception {
        mockMvc.perform(get(path(listingId)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productListingId").value(listingId))
                .andExpect(jsonPath("$.data.platform").value("COUPANG"))
                .andExpect(jsonPath("$.data.platformProductId").value("222333444"))
                .andExpect(jsonPath("$.data.productName").value("노브랜드 생수 2L 6입"))
                // 두 목록은 분리되어 있고, 마켓이 준 순서 그대로다.
                .andExpect(jsonPath("$.data.thumbnailImages.length()").value(2))
                .andExpect(jsonPath("$.data.thumbnailImages[0]")
                        .value("https://img1.coupangcdn.com/thumb-a.jpg"))
                .andExpect(jsonPath("$.data.detailImages.length()").value(2))
                .andExpect(jsonPath("$.data.detailImages[0]")
                        .value("https://img1.coupangcdn.com/detail-1.jpg"))
                .andExpect(jsonPath("$.data.detailImages[1]")
                        .value("https://img1.coupangcdn.com/detail-2.jpg"));
    }

    @Test
    void testImages200EmptyListsWhenMarketHasNone() throws Exception {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(COUPANG_PRODUCT_NO_IMAGES);

        mockMvc.perform(get(path(listingId)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thumbnailImages.length()").value(0))
                .andExpect(jsonPath("$.data.detailImages.length()").value(0));
    }

    // ---- 400 (500 이 되면 안 된다) ----

    @Test
    void testImagesMissingPlatformProductId400() throws Exception {
        Long noProductId = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).name("ID 없는 셀")
                .status(ListingStatus.DRAFT).seller(seller).build()).getId();

        mockMvc.perform(get(path(noProductId)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testImagesUnsupportedPlatform400() throws Exception {
        Long naverCell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.NAVER).platformProductId("999888777").name("네이버 셀")
                .status(ListingStatus.SELLING).seller(seller).build()).getId();

        mockMvc.perform(get(path(naverCell)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    // ---- tenant scope ----

    /**
     * 테넌트 2 의 셀을 테넌트 1 로 조회하면 404. Hibernate 는 세션이 열릴 때 테넌트를 한 번만 정하므로
     * (BaseIntegrationTest 는 트랜잭션 하나로 돈다) 저장 후 <b>네이티브 UPDATE 로</b> 소속을 옮긴다 —
     * 테넌트 필터를 우회해 "남의 테넌트 행" 을 만드는 유일한 방법이다.
     */
    @Test
    void testImagesOtherTenantCell404() throws Exception {
        productListingRepository.flush();
        jdbcTemplate.update("update product_listing set tenant_id = 2 where id = ?", listingId);

        mockMvc.perform(get(path(listingId)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void testImagesUnauthorized401() throws Exception {
        mockMvc.perform(get(path(listingId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testImagesForbidden403() throws Exception {
        mockMvc.perform(get(path(listingId)).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
