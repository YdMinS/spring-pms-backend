package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [마스터 카테고리로 변경] 토글 (FEATURE_2609_45 / D13) — 권한(401/403)·happy path·없는 셀 404.
 * 비우기 규칙 자체는 {@code ListingCategorySourceServiceTest} 가 단위로 덮는다.
 */
class ListingCategorySourceControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;

    private static final String PATH = "/api/admin/product-listings";
    private static final String BODY = "{\"useMasterCategory\":true}";

    private Long listingId;
    private Long optionId;

    @BeforeEach
    void seedChannelOwnedCategoryCell() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        Category category = categoryRepository.save(Category.builder().name("즉석밥").build());
        PlatformCategory masterCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("58630").name("즉석밥")
                .commissionRate(new BigDecimal("0.11")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform(Platform.COUPANG).platformCategoryId("58630")
                .platformCategory(masterCategory).build());
        // The channel's own category — a different node that also carries a commission (D11 satisfied).
        platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("73170").name("즉석식품")
                .commissionRate(new BigDecimal("0.11")).build());

        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("즉석밥 마스터").active(true).category(category).build());
        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("X").name("셀").seller(seller)
                .masterProduct(master)
                .platformCategoryCode("73170").categoryNoticeGroup("가공식품").build());
        listingId = listing.getId();
        optionId = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(listing).optionName("기본").sellingPrice(BigDecimal.ZERO)
                .categoryAttributes(Map.of("수량", "6"))
                .categoryNotices(Map.of("품목 또는 명칭", "쌀")).build()).getId();
    }

    /** The listing graph FK-references master/seller — remove it before the base cleanup runs. */
    @AfterEach
    void cleanupListingGraph() {
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
        categoryMappingRepository.deleteAll();
        masterProductRepository.deleteAll();
    }

    @Test
    void updateCategorySource_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/category-source")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateCategorySource_userToken_returns403() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/category-source")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateCategorySource_adminToken_clearsChannelCategory() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/category-source")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productListingId").value(listingId))
                .andExpect(jsonPath("$.data.useMasterCategory").value(true))
                .andExpect(jsonPath("$.data.previousCategoryCode").value("73170"))
                // 전환 후에는 마스터 카테고리로 해석된다 — 프론트의 "A → B" 안내 재료.
                .andExpect(jsonPath("$.data.effectiveCategoryCode").value("58630"));

        productListingRepository.flush();
        productListingOptionRepository.flush();
        ProductListing cell = productListingRepository.findById(listingId).orElseThrow();
        assertThat(cell.getPlatformCategoryCode()).isNull();
        assertThat(cell.getCategoryNoticeGroup()).isNull();
        ProductListingOption option = productListingOptionRepository.findById(optionId).orElseThrow();
        assertThat(option.getCategoryAttributes()).isNull();
        assertThat(option.getCategoryNotices()).isNull();
    }

    @Test
    void updateCategorySource_missingCell_returns404() throws Exception {
        mockMvc.perform(patch(PATH + "/999999/category-source")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
