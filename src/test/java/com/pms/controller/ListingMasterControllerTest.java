package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.ListingMasterCreateRequest;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 판매상품 → 마스터 생성 엔드포인트 2개(FEATURE_2609_22 / 04): 권한(401/403) + 미리보기·생성 happy path +
 * 요청 검증. {@link CoupangApiClient} 는 목이라 라이브 호출이 없다(이 기능은 어차피 읽기 1회뿐이다).
 */
class ListingMasterControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    /**
     * 옵션 2건짜리 쿠팡 조회 응답. ⚠️ 첫 옵션의 {@code vendorItemId} 는 셀에 저장된 값과 <b>다르다</b> —
     * D27 자동 교정 대상이 미리보기에서 {@code optionIdMismatch=true} 로 보이는지가 이 테스트의 핵심이다.
     */
    private static final String COUPANG_PRODUCT =
            "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":222333444,"
                    + "\"sellerProductName\":\"노브랜드 생수 2L 6입/12입\",\"displayCategoryCode\":\"63955\","
                    + "\"statusName\":\"승인완료\",\"items\":["
                    + "{\"itemName\":\"6입\",\"vendorItemId\":8123456789,\"sellerProductItemId\":9123,"
                    + "\"salePrice\":5900,\"originalPrice\":5900,\"maximumBuyCount\":50},"
                    + "{\"itemName\":\"12입\",\"vendorItemId\":8123456790,\"sellerProductItemId\":9124,"
                    + "\"salePrice\":10900,\"originalPrice\":10900,\"maximumBuyCount\":50}]}}";

    private Long listingId;
    private Long categoryId;

    @BeforeEach
    void seed() throws Exception {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        Product product = productRepository.save(Product.builder()
                .productName("생수 2L").brand("노브랜드")
                .price(new BigDecimal("700")).imageUrl("products/p.jpg").active(true).build());

        Category category = categoryRepository.save(Category.builder().name("생수").build());
        categoryId = category.getId();
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("63955").name("생수")
                .commissionRate(new BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform(Platform.COUPANG).platformCategoryId("63955")
                .platformCategory(platformCategory).build());

        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("메인")
                .vendorId("V1").vendorUserId("wing-user")
                .accessKey("ak").secretKey("sk").isActive(true).build());

        // legacy `판매상품 등록` 으로 만들어진 셀 = 마스터도 마스터 옵션 FK 도 없다.
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("222333444").name("노브랜드 생수 2L")
                .status(ListingStatus.SELLING).seller(seller).build());
        listingId = cell.getId();
        saveOption(cell, "6입", "8123456780", "5900", product, 6);
        saveOption(cell, "12입", "8123456790", "10900", product, 12);

        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(COUPANG_PRODUCT);
    }

    private void saveOption(ProductListing cell, String name, String platformOptionId, String price,
                            Product product, int quantity) {
        ProductListingOption option = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName(name).platformOptionId(platformOptionId)
                .sellingPrice(new BigDecimal(price)).stockQuantity(50).active(true).build());
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(option).product(product).quantity(quantity).build());
    }

    private String previewPath() {
        return "/api/admin/product-listings/" + listingId + "/master/preview";
    }

    private String createPath() {
        return "/api/admin/product-listings/" + listingId + "/master";
    }

    private String createBody() throws Exception {
        return objectMapper.writeValueAsString(ListingMasterCreateRequest.builder()
                .masterName("노브랜드 생수 2L").categoryId(categoryId).build());
    }

    // ---- happy path ----

    @Test
    void testPreview200() throws Exception {
        mockMvc.perform(post(previewPath()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.coupangProductName").value("노브랜드 생수 2L 6입/12입"))
                .andExpect(jsonPath("$.data.suggestedMasterName").value("노브랜드 생수 2L"))
                .andExpect(jsonPath("$.data.options.length()").value(2))
                // D27: 셀의 옵션 id 가 쿠팡과 다르면 미리보기가 알려준다(커밋이 교정한다).
                .andExpect(jsonPath("$.data.options[0].optionIdMismatch").value(true))
                .andExpect(jsonPath("$.data.options[1].optionIdMismatch").value(false))
                .andExpect(jsonPath("$.data.suggestedCategoryId").value(categoryId));
    }

    @Test
    void testCreate200() throws Exception {
        mockMvc.perform(post(createPath()).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.masterProductId").isNumber())
                .andExpect(jsonPath("$.data.productListingId").value(listingId))
                .andExpect(jsonPath("$.data.optionCount").value(2));
    }

    // ---- request validation ----

    @Test
    void testCreateInvalidBody400() throws Exception {
        String body = objectMapper.writeValueAsString(ListingMasterCreateRequest.builder()
                .masterName("노브랜드 생수 2L").build());

        mockMvc.perform(post(createPath()).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void testCreateUnauthorized401() throws Exception {
        mockMvc.perform(post(createPath())
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testPreviewForbidden403() throws Exception {
        mockMvc.perform(post(previewPath()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
