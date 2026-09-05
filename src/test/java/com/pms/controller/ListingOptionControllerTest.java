package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProduct;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MarginPolicyRepository;
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
    @Autowired private MarginPolicyRepository marginPolicyRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long listingId;
    private Long opt1Id;
    private Long opt2Id;
    private Long opt3Id;
    /** 87: a second cell that reached the market, whose opt-a carries a vendorItemId (= cannot be unchecked). */
    private Long marketListingId;
    private Long marketOptAId;
    private Long marketOptBId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        categoryRepository.save(Category.builder()
                .name("신발").platform("COUPANG").platformCategoryId("cat-1").build());
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true).build());
        // 2609_19/D7: a manual price also refreshes the display price, which needs the seller×platform preset.
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform("COUPANG").marginRate(new BigDecimal("0.1500")).build());
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId(null).name("셀").status(ListingStatus.DRAFT)
                .seller(seller).masterProduct(master).build());
        listingId = cell.getId();
        opt1Id = saveOption(cell, "opt1");
        opt2Id = saveOption(cell, "opt2");
        opt3Id = saveOption(cell, "opt3");

        ProductListing marketCell = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("P-1").name("마켓 셀").status(ListingStatus.SELLING)
                .seller(seller).masterProduct(master).build());
        marketListingId = marketCell.getId();
        marketOptAId = saveOption(marketCell, "market-a", "V-1");
        marketOptBId = saveOption(marketCell, "market-b", null);
    }

    private Long saveOption(ProductListing cell, String name) {
        return saveOption(cell, name, null);
    }

    private Long saveOption(ProductListing cell, String name, String platformOptionId) {
        return productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName(name).sellingPrice(new BigDecimal("6000"))
                .platformOptionId(platformOptionId).approvalStatus(OptionApprovalStatus.NOT_APPROVED)
                .build()).getId();
    }

    private String optionsPath() {
        return "/api/admin/product-listings/" + listingId + "/options";
    }

    private String activePath(Long id) {
        return "/api/admin/product-listings/" + id + "/options/active";
    }

    private String stockPath(Long id) {
        return "/api/admin/product-listings/" + id + "/options/stock";
    }

    private String pricePath(Long id) {
        return "/api/admin/product-listings/" + id + "/options/price";
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
        mockMvc.perform(put(activePath(listingId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[" + opt1Id + "]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setActive_userToken_returns403() throws Exception {
        mockMvc.perform(put(activePath(listingId)).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[" + opt1Id + "]}"))
                .andExpect(status().isForbidden());
    }

    // ---- PUT happy + DB assertion: opt2 deactivated persists ----

    @Test
    void setActive_adminToken_deactivatesUnlistedOption() throws Exception {
        mockMvc.perform(put(activePath(listingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[" + opt1Id + "," + opt3Id + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options.length()").value(3));

        assertThat(productListingOptionRepository.findById(opt1Id).orElseThrow().getActive()).isTrue();
        assertThat(productListingOptionRepository.findById(opt2Id).orElseThrow().getActive()).isFalse();
        assertThat(productListingOptionRepository.findById(opt3Id).orElseThrow().getActive()).isTrue();
    }

    // ---- 87: an option registered on the market cannot be unchecked ----

    @Test
    void setActive_uncheckingMarketRegisteredOption_returns400() throws Exception {
        mockMvc.perform(put(activePath(marketListingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[" + marketOptBId + "]}"))
                .andExpect(status().isBadRequest());

        assertThat(productListingOptionRepository.findById(marketOptAId).orElseThrow().getActive()).isTrue();
    }

    // ---- PUT empty set → 400 ----

    @Test
    void setActive_emptySet_returns400() throws Exception {
        mockMvc.perform(put(activePath(listingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeOptionIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // ---- 102: per-channel option stock ----

    @Test
    void setStock_noToken_returns401() throws Exception {
        mockMvc.perform(put(stockPath(listingId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stocks\":[{\"optionId\":" + opt1Id + ",\"stockQuantity\":30}]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setStock_userToken_returns403() throws Exception {
        mockMvc.perform(put(stockPath(listingId)).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stocks\":[{\"optionId\":" + opt1Id + ",\"stockQuantity\":30}]}"))
                .andExpect(status().isForbidden());
    }

    // The master leaves stock unset → the ceiling is the 9999 fallback, so 30 is accepted and persists.
    @Test
    void setStock_adminToken_savesOnlyTheListedOption() throws Exception {
        mockMvc.perform(put(stockPath(listingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stocks\":[{\"optionId\":" + opt1Id + ",\"stockQuantity\":30}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options.length()").value(3));

        assertThat(productListingOptionRepository.findById(opt1Id).orElseThrow().getStockQuantity()).isEqualTo(30);
        assertThat(productListingOptionRepository.findById(opt2Id).orElseThrow().getStockQuantity()).isNull();
    }

    @Test
    void setStock_emptyList_returns400() throws Exception {
        mockMvc.perform(put(stockPath(listingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stocks\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // ---- 2609_19: manual channel price ----

    @Test
    void setPrice_noToken_returns401() throws Exception {
        mockMvc.perform(put(pricePath(listingId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\":[{\"optionId\":" + opt1Id + ",\"sellingPrice\":15000}]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setPrice_userToken_returns403() throws Exception {
        mockMvc.perform(put(pricePath(listingId)).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\":[{\"optionId\":" + opt1Id + ",\"sellingPrice\":15000}]}"))
                .andExpect(status().isForbidden());
    }

    // The cell is DRAFT and its options carry no vendorItemId → saved locally and reported as skipped (D5).
    @Test
    void setPrice_adminToken_savesManualPriceAndReportsSkipped() throws Exception {
        mockMvc.perform(put(pricePath(listingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\":[{\"optionId\":" + opt1Id + ",\"sellingPrice\":15000}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushed").value(0))
                .andExpect(jsonPath("$.data.skipped[0]").value("opt1"))
                .andExpect(jsonPath("$.data.listing.options.length()").value(3));

        ProductListingOption saved = productListingOptionRepository.findById(opt1Id).orElseThrow();
        assertThat(saved.getSellingPrice()).isEqualByComparingTo("15000");
        assertThat(saved.getPriceSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        assertThat(productListingOptionRepository.findById(opt2Id).orElseThrow().getPriceSource())
                .isEqualTo(GeneratedContentSource.AUTO);
    }

    @Test
    void setPrice_belowMinimum_returns400() throws Exception {
        mockMvc.perform(put(pricePath(listingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\":[{\"optionId\":" + opt1Id + ",\"sellingPrice\":5}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setPrice_unknownListing_returns404() throws Exception {
        mockMvc.perform(put(pricePath(999999L)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\":[{\"optionId\":" + opt1Id + ",\"sellingPrice\":15000}]}"))
                .andExpect(status().isNotFound());
    }
}
