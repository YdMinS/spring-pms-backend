package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProduct;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.CategoryRepository;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.MarketplaceAccountRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long listingId;
    private Long opt1Id;
    private Long opt2Id;
    private Long opt3Id;
    /** 87: a second cell that reached the market, whose opt-a carries a vendorItemId (= cannot be unchecked). */
    private Long marketListingId;
    private Long marketOptAId;
    private Long marketOptBId;
    private Seller seller;

    @BeforeEach
    void seed() {
        seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        categoryRepository.save(Category.builder()
                .name("신발").platform(Platform.COUPANG).platformCategoryId("cat-1").build());
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true).build());
        // 2609_19/D7: a manual price also refreshes the display price, which needs the seller×platform preset.
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform(Platform.COUPANG).marginRate(new BigDecimal("0.1500")).build());
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId(null).name("셀").status(ListingStatus.DRAFT)
                .seller(seller).masterProduct(master).build());
        listingId = cell.getId();
        opt1Id = saveOption(cell, "opt1");
        opt2Id = saveOption(cell, "opt2");
        opt3Id = saveOption(cell, "opt3");

        ProductListing marketCell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("P-1").name("마켓 셀").status(ListingStatus.SELLING)
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

    private String namePath(Long id) {
        return "/api/admin/product-listings/" + id + "/options/name";
    }

    private String applyNamesPath(Long id) {
        return "/api/admin/product-listings/" + id + "/options/apply-master-names";
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
                .andExpect(jsonPath("$.data.listing.options.length()").value(3))
                // FEATURE_2611_19 / D7: a DRAFT cell has no market numbers → saved only
                .andExpect(jsonPath("$.data.pushed").value(0))
                .andExpect(jsonPath("$.data.skipped[0]").value("opt1"));

        assertThat(productListingOptionRepository.findById(opt1Id).orElseThrow().getStockQuantity()).isEqualTo(30);
        assertThat(productListingOptionRepository.findById(opt2Id).orElseThrow().getStockQuantity()).isNull();
    }

    // FEATURE_2611_19 / D7 · D16 · D26: a cell on the market — the save commits, then the option with a Coupang option
    // id is sent (0 as 0); the other is saved only; no [수정 요청] prompt.
    @Test
    void setStock_marketCell_sendsTheNumberedOption_andReportsTheOther() throws Exception {
        marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder().seller(seller).build());
        given(coupangApiClient.put(anyString(), anyString(), any())).willReturn("{\"code\":\"SUCCESS\"}");

        mockMvc.perform(put(stockPath(marketListingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stocks\":[{\"optionId\":" + marketOptAId + ",\"stockQuantity\":0},"
                                + "{\"optionId\":" + marketOptBId + ",\"stockQuantity\":4}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushed").value(1))
                .andExpect(jsonPath("$.data.skipped[0]").value("market-b"))
                .andExpect(jsonPath("$.data.failed.length()").value(0))
                .andExpect(jsonPath("$.data.listing.needsResync").value(false));

        verify(coupangApiClient).put(
                eq("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/V-1/quantities/0"), eq("{}"), any());
        assertThat(productListingOptionRepository.findById(marketOptAId).orElseThrow().getStockQuantity()).isZero();
    }

    // FEATURE_2611_19 / D6: the market refuses → the stock stays saved and the refusal comes back as a failed line.
    @Test
    void setStock_marketRefuses_stockStaysSaved_failedLine() throws Exception {
        marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder().seller(seller).build());
        given(coupangApiClient.put(anyString(), anyString(), any()))
                .willThrow(new IllegalStateException("삭제된 상품은 변경이 불가능합니다"));

        mockMvc.perform(put(stockPath(marketListingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stocks\":[{\"optionId\":" + marketOptAId + ",\"stockQuantity\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushed").value(0))
                .andExpect(jsonPath("$.data.failed[0].optionName").value("market-a"))
                .andExpect(jsonPath("$.data.failed[0].message").value("삭제된 상품은 변경이 불가능합니다"));

        assertThat(productListingOptionRepository.findById(marketOptAId).orElseThrow().getStockQuantity())
                .isEqualTo(3);
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

    // ---- 2609_22: per-channel option names ----

    @Test
    void setOptionNames_noToken_returns401() throws Exception {
        mockMvc.perform(put(namePath(listingId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[{\"optionId\":" + opt1Id + ",\"optionName\":\"새 이름\"}]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setOptionNames_userToken_returns403() throws Exception {
        mockMvc.perform(put(namePath(listingId)).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[{\"optionId\":" + opt1Id + ",\"optionName\":\"새 이름\"}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setOptionNames_adminToken_savesNameAndMarksManualOverride() throws Exception {
        mockMvc.perform(put(namePath(listingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[{\"optionId\":" + opt1Id + ",\"optionName\":\"생수 6개입\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options.length()").value(3))
                .andExpect(jsonPath("$.data.options[0].optionNameSource").value("MANUAL_OVERRIDE"))
                .andExpect(jsonPath("$.data.options[0].channelOnly").value(true));

        ProductListingOption saved = productListingOptionRepository.findById(opt1Id).orElseThrow();
        assertThat(saved.getOptionName()).isEqualTo("생수 6개입");
        assertThat(saved.getOptionNameSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        // Untouched options keep AUTO (the write is partial).
        assertThat(productListingOptionRepository.findById(opt2Id).orElseThrow().getOptionNameSource())
                .isEqualTo(GeneratedContentSource.AUTO);
    }

    @Test
    void setOptionNames_emptyBody_returns400() throws Exception {
        mockMvc.perform(put(namePath(listingId)).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // ---- 2609_74: channel [마스터 옵션명 반영] ----

    @Test
    void applyMasterNames_noToken_returns401() throws Exception {
        mockMvc.perform(post(applyNamesPath(listingId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void applyMasterNames_userToken_returns403() throws Exception {
        mockMvc.perform(post(applyNamesPath(listingId)).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void applyMasterNames_adminToken_returns200() throws Exception {
        mockMvc.perform(post(applyNamesPath(listingId)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedOptions").exists())
                .andExpect(jsonPath("$.data.skippedAwaitingId").isArray());
    }
}
