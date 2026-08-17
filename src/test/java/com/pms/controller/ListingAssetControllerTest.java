package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.CommissionRate;
import com.pms.domain.MarginPolicy;
import com.pms.domain.Package;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.repository.CommissionRateRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ImageStorageService;
import com.pms.service.ProductImageLoader;
import com.pms.service.ThumbnailRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listing-asset endpoints: regenerate authority (401/403/200) + 404, and the generated read (404 before,
 * 200 after). {@link ThumbnailRenderer} / {@link ImageStorageService} / {@link ProductImageLoader} are
 * mocked so the render runs without disk/network. Price precision is covered by {@code PriceCalculatorTest}.
 */
class ListingAssetControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;
    @Autowired private MarginPolicyRepository marginPolicyRepository;
    @Autowired private CommissionRateRepository commissionRateRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;
    // carrierRepository / carrierRateRepository / packageRepository are inherited from BaseIntegrationTest.

    @MockBean private ThumbnailRenderer thumbnailRenderer;
    @MockBean private ImageStorageService imageStorageService;
    @MockBean private ProductImageLoader productImageLoader;

    private static final String PATH = "/api/admin/product-listings";
    private Long listingId;

    @BeforeEach
    void seedCell() throws Exception {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        Product product = productRepository.save(Product.builder()
                .productName("운동화").brand("나이키")
                .price(new BigDecimal("1500")).imageUrl("products/p.jpg").active(true).build());

        // Commission (COUPANG default) + margin preset so the price engine resolves.
        commissionRateRepository.save(CommissionRate.builder()
                .platform("COUPANG").category(null).rate(new BigDecimal("0.10")).isDefault(true).build());
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform("COUPANG").marginRate(new BigDecimal("0.1500")).build());

        // Fresh delivery (carrier rate) + box (package) created in this flow so both FK targets exist
        // under the same session tenant as the listing insert (avoids the @Transactional session-tenant
        // mismatch with base-seeded rows). delivery 2500 + box 500 keep the price arithmetic clean.
        Carrier carrier = carrierRepository.saveAndFlush(Carrier.builder().name("CJ").isActive(true).build());
        CarrierRate delivery = carrierRateRepository.saveAndFlush(CarrierRate.builder()
                .carrier(carrier).type("STANDARD").cost(new BigDecimal("2500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        Package box = packageRepository.saveAndFlush(Package.builder()
                .type("M").cost(new BigDecimal("500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());

        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("X").name("셀").seller(seller)
                .delivery(delivery).package_(box)
                .build());
        listingId = listing.getId();
        ProductListingOption option = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(listing).optionName("기본").sellingPrice(BigDecimal.ZERO).build());
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(option).product(product).quantity(1).build());

        given(productImageLoader.load(any())).willReturn(new byte[]{1, 2, 3});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{4, 5, 6});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/generated.jpg");

        // Create the tenant default template within the test session. The startup-seeded default lives
        // under tenant 1, which the @Transactional test session does not resolve — so regenerate would
        // otherwise get "기본 템플릿이 없습니다" (mirrors ProductThumbnailControllerTest). Renderer is mocked,
        // so no elements are needed.
        String templateJson = objectMapper.writeValueAsString(java.util.Map.of(
                "name", "쿠팡 기본", "canvasWidth", 300, "canvasHeight", 300,
                "active", true, "isDefault", true,
                "fields", java.util.List.of(), "elements", java.util.List.of()));
        mockMvc.perform(post("/api/admin/thumbnail-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(templateJson))
                .andExpect(status().isCreated());
    }

    /**
     * Delete the listing graph before {@code BaseIntegrationTest.cleanupTestData()} deletes package /
     * carrier_rate — the seeded product_listing FK-references them (subclass @AfterEach runs first).
     */
    @AfterEach
    void cleanupListingGraph() {
        generatedProductDataRepository.deleteAll();
        productListingProductRepository.deleteAll();
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
    }

    // ---- regenerate authority (MUST-KEEP) ----

    @Test
    void regenerate_noToken_returns401() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/regenerate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regenerate_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/regenerate")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void regenerate_adminToken_returns200WithAssetsAndPrice() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/regenerate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productListingId").value(listingId))
                .andExpect(jsonPath("$.data.thumbnailUrl").value("thumbnails/generated.jpg"))
                .andExpect(jsonPath("$.data.optionPrices.length()").value(1))
                // (1500 + 2500 + 500) / 0.75 = 6000, rounded to nearest 10 won
                .andExpect(jsonPath("$.data.optionPrices[0].sellingPrice").value(6000.00));
    }

    @Test
    void regenerate_missingCell_returns404() throws Exception {
        mockMvc.perform(post(PATH + "/999999/regenerate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ---- generated read: 404 before generation, 200 after ----

    @Test
    void getGenerated_beforeRegenerate_returns404() throws Exception {
        mockMvc.perform(get(PATH + "/" + listingId + "/generated")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getGenerated_afterRegenerate_returns200() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/regenerate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(PATH + "/" + listingId + "/generated")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thumbnailUrl").value("thumbnails/generated.jpg"));
    }

    // ---- field-values override (12): authority + reflected response + 404 ----

    private static final String FIELD_VALUES_BODY = "{\"fieldValues\":{\"brandName\":\"직접입력\"}}";

    @Test
    void updateFieldValues_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/field-values")
                        .contentType("application/json").content(FIELD_VALUES_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateFieldValues_userToken_returns403() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/field-values")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(FIELD_VALUES_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateFieldValues_adminToken_returns200WithReflectedOverride() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/field-values")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(FIELD_VALUES_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productListingId").value(listingId))
                .andExpect(jsonPath("$.data.fieldValues.brandName").value("직접입력"))
                // regeneration still runs: option price recomputed, thumbnail written.
                .andExpect(jsonPath("$.data.thumbnailUrl").value("thumbnails/generated.jpg"))
                .andExpect(jsonPath("$.data.optionPrices[0].sellingPrice").value(6000.00));
    }

    @Test
    void updateFieldValues_missingCell_returns404() throws Exception {
        mockMvc.perform(patch(PATH + "/999999/field-values")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(FIELD_VALUES_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
