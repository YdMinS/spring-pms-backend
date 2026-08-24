package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-channel option endpoints (FEATURE_2608_06 / 42): authority (401/403/200) on GET/PUT, the PUT bulk set with a
 * DB assertion (a deactivated option persists), and the empty-set 400. {@link CoupangApiClient} is mocked (05
 * convention — no live HTTP); the option endpoints do not call the adapter.
 */
class ListingOptionControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long listingId;
    private Long opt1Id;
    private Long opt2Id;
    private Long opt3Id;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        categoryRepository.save(Category.builder()
                .name("신발").platform("COUPANG").platformCategoryId("cat-1").build());
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true).build());
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId(null).name("셀").status(ListingStatus.DRAFT)
                .seller(seller).masterProduct(master).build());
        listingId = cell.getId();
        opt1Id = saveOption(cell, "opt1");
        opt2Id = saveOption(cell, "opt2");
        opt3Id = saveOption(cell, "opt3");
    }

    private Long saveOption(ProductListing cell, String name) {
        return productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName(name).sellingPrice(new BigDecimal("6000")).build()).getId();
    }

    private String optionsPath() {
        return "/api/admin/product-listings/" + listingId + "/options";
    }

    private String activePath() {
        return "/api/admin/product-listings/" + listingId + "/options/active";
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void getOptions_noToken_returns401() throws Exception {
        mockMvc.perform(get(optionsPath())).andExpect(status().isUnauthorized());
    }

    @Test
    void getOptions_userToken_returns403() throws Exception {
        mockMvc.perform(get(optionsPath()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOptions_adminToken_returns200AllOptions() throws Exception {
        mockMvc.perform(get(optionsPath()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options.length()").value(3))
                .andExpect(jsonPath("$.data.needsResync").value(false));
    }

    @Test
    void setActive_noToken_returns401() throws Exception {
        mockMvc.perform(put(activePath()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[" + opt1Id + "]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setActive_userToken_returns403() throws Exception {
        mockMvc.perform(put(activePath()).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[" + opt1Id + "]}"))
                .andExpect(status().isForbidden());
    }

    // ---- PUT happy + DB assertion: opt2 deactivated persists ----

    @Test
    void setActive_adminToken_deactivatesUnlistedOption() throws Exception {
        mockMvc.perform(put(activePath()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[" + opt1Id + "," + opt3Id + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options.length()").value(3));

        assertThat(productListingOptionRepository.findById(opt1Id).orElseThrow().getActive()).isTrue();
        assertThat(productListingOptionRepository.findById(opt2Id).orElseThrow().getActive()).isFalse();
        assertThat(productListingOptionRepository.findById(opt3Id).orElseThrow().getActive()).isTrue();
    }

    // ---- PUT empty set → 400 ----

    @Test
    void setActive_emptySet_returns400() throws Exception {
        mockMvc.perform(put(activePath()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
