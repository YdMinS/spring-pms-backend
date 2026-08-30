package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceShippingConfig;
import com.pms.domain.CategoryMapping;
import com.pms.domain.PlatformCategory;
import com.pms.domain.MasterProduct;
import com.pms.domain.Package;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.CategoryRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MarketplaceShippingConfigRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductListingTagRevisionRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Channel registration endpoints (FEATURE_2608_06 / 3c): authority (401/403/200) on register + register happy
 * (SUBMITTED), fetch-status guard, and sync-approvals wiring. {@link CoupangApiClient} is mocked so no live
 * HTTP is made (the real adapter/resolver still run).
 */
class ListingRegistrationControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;
    @Autowired private ProductListingTagRevisionRepository productListingTagRevisionRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private MarketplaceShippingConfigRepository marketplaceShippingConfigRepository;

    @MockBean private CoupangApiClient coupangApiClient;

    private Long draftCellId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        Category category = categoryRepository.save(Category.builder().name("신발").build());
        Carrier carrier = carrierRepository.saveAndFlush(Carrier.builder().name("CJ").isActive(true).build());
        CarrierRate delivery = carrierRateRepository.saveAndFlush(CarrierRate.builder()
                .carrier(carrier).type("STANDARD").cost(new BigDecimal("2500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        Package box = packageRepository.saveAndFlush(Package.builder()
                .type("M").cost(new BigDecimal("500")).effectiveDate(LocalDate.now()).isDefault(false).build());
        // Standard category on the master (44) + a COUPANG mapping: the adapter payload resolves
        // displayCategoryCode from the mapping.
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true).category(category).build());
        // 52: the mapping's linked PlatformCategory owns the mall code — the adapter payload resolves
        // displayCategoryCode from it.
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform("COUPANG").code("cat-1").name("운동화")
                .commissionRate(new BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform("COUPANG").platformCategoryId("cat-1")
                .platformCategory(platformCategory).build());

        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId(null).name("셀").status(ListingStatus.DRAFT)
                .seller(seller).category(category).delivery(delivery).package_(box).masterProduct(master).build());
        draftCellId = cell.getId();
        productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName("기본").sellingPrice(new BigDecimal("6000")).build());
        generatedProductDataRepository.save(GeneratedProductData.builder()
                .productListing(cell).thumbnailUrl("thumbnails/t.jpg").detailHtml("<p>셀</p>")
                .source(GeneratedContentSource.AUTO).generatedAt(LocalDateTime.now()).build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").accountAlias("메인")
                .vendorId("V1").vendorUserId("wing-user")   // 73: WING login id (register-required)
                .accessKey("ak").secretKey("sk").isActive(true).build());
        // 73: register payload needs a complete per-account shipping config (72) → 400 otherwise.
        marketplaceShippingConfigRepository.save(MarketplaceShippingConfig.builder()
                .marketplaceAccount(account)
                .outboundShippingPlaceCode("OUT-1")
                .returnCenterCode("RC-1").returnChargeName("반품담당").returnContactNumber("021234567")
                .returnZipCode("06000").returnAddress("서울시 강남구").returnAddressDetail("1층")
                .returnCharge(new BigDecimal("2500")).deliveryChargeOnReturn(new BigDecimal("2500"))
                .deliveryMethod("SEQUENCIAL").deliveryCompanyCode("KGB").deliveryChargeType("FREE")
                .deliveryCharge(new BigDecimal("0")).remoteAreaDeliverable("N")
                .unionDeliveryType("NOT_UNION_DELIVERY").build());

        // Coupang register response → sellerProductId (no live HTTP).
        given(coupangApiClient.post(anyString(), anyString(), any()))
                .willReturn("{\"code\":\"SUCCESS\",\"data\":123456789}");
    }

    /** Delete the listing graph before base cleanup removes package / carrier_rate (FK targets). */
    @AfterEach
    void cleanupListingGraph() {
        generatedProductDataRepository.deleteAll();
        // A successful register appends a ProductListingTagRevision (33) — delete it before the listing (FK).
        productListingTagRevisionRepository.deleteAll();
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
    }

    private String registerPath() {
        return "/api/admin/product-listings/" + draftCellId + "/register";
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void register_noToken_returns401() throws Exception {
        mockMvc.perform(post(registerPath())).andExpect(status().isUnauthorized());
    }

    @Test
    void register_userToken_returns403() throws Exception {
        mockMvc.perform(post(registerPath()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void register_adminToken_returns200Submitted() throws Exception {
        mockMvc.perform(post(registerPath()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.platformProductId").value("123456789"));
    }

    // ---- fetch-status guard (2nd endpoint wiring): DRAFT cell has no market id → 400 ----

    @Test
    void fetchStatus_draftCell_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/product-listings/" + draftCellId + "/fetch-status")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    // ---- sync-approvals wiring (3rd endpoint): empty sweep → 200 summary ----

    @Test
    void syncApprovals_adminToken_returns200() throws Exception {
        mockMvc.perform(post("/api/admin/listings/sync-approvals")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.swept").isNumber());
    }
}
