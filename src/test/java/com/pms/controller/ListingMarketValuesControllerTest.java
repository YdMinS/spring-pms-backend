package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
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
import org.springframework.test.context.TestConstructor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [마스터 값으로 바꾸기] (FEATURE_2611_27 / D20) — 401 / 403 · happy path · a group not in use (400) · missing cell
 * (404). The per-group rules are covered by {@code ListingMarketValueServiceTest}.
 */
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ListingMarketValuesControllerTest extends BaseIntegrationTest {

    private final SellerRepository sellerRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final MasterProductRepository masterProductRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;

    ListingMarketValuesControllerTest(SellerRepository sellerRepository, CategoryRepository categoryRepository,
                                      CategoryMappingRepository categoryMappingRepository,
                                      PlatformCategoryRepository platformCategoryRepository,
                                      MasterProductRepository masterProductRepository,
                                      ProductListingRepository productListingRepository,
                                      ProductListingOptionRepository productListingOptionRepository) {
        this.sellerRepository = sellerRepository;
        this.categoryRepository = categoryRepository;
        this.categoryMappingRepository = categoryMappingRepository;
        this.platformCategoryRepository = platformCategoryRepository;
        this.masterProductRepository = masterProductRepository;
        this.productListingRepository = productListingRepository;
        this.productListingOptionRepository = productListingOptionRepository;
    }

    private static final String PATH = "/api/admin/product-listings";
    private static final String TAGS_BODY = "{\"group\":\"TAGS\"}";

    private Long listingId;

    @BeforeEach
    void seedMarketTagsCell() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        Category category = categoryRepository.save(Category.builder().name("즉석밥").build());
        PlatformCategory masterCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("58630").name("즉석밥")
                .commissionRate(new BigDecimal("0.11")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform(Platform.COUPANG).platformCategoryId("58630")
                .platformCategory(masterCategory).build());

        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("즉석밥 마스터").active(true).category(category).build());
        listingId = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("X").name("셀").seller(seller)
                .masterProduct(master)
                .usesMarketTags(true).tags(List.of("물")).build()).getId();
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
    void useMasterValues_noToken_returns401() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/use-master-values")
                        .contentType("application/json").content(TAGS_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void useMasterValues_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/use-master-values")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(TAGS_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void useMasterValues_adminToken_tags_clearsTagsAndFlag() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/use-master-values")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(TAGS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productListingId").value(listingId))
                .andExpect(jsonPath("$.data.usesMarketTags").value(false));

        productListingRepository.flush();
        ProductListing cell = productListingRepository.findById(listingId).orElseThrow();
        assertThat(cell.getTags()).isNull();
        assertThat(cell.isUsesMarketTags()).isFalse();
    }

    @Test
    void useMasterValues_groupNotInUse_returns400() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/use-master-values")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"group\":\"ATTRIBUTES\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("마켓 값을 쓰지 않는 묶음입니다"));
    }

    @Test
    void useMasterValues_missingCell_returns404() throws Exception {
        mockMvc.perform(post(PATH + "/999999/use-master-values")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(TAGS_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
