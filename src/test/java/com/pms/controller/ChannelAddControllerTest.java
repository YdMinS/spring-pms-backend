package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CommissionRate;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Package;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.dto.request.ChannelAddRequest;
import com.pms.domain.MasterProductCategory;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.MasterProductCategoryRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Channel-add endpoint (FEATURE_2608_06 / 3b'): authority (401/403/201) + generated price on the new DRAFT
 * cell, and the duplicate-channel 409. Renderer / storage / image-loader are mocked so the reused seam runs
 * without disk/network (mirrors {@code ListingAssetControllerTest}).
 */
class ChannelAddControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CommissionRateRepository commissionRateRepository;
    @Autowired private MarginPolicyRepository marginPolicyRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductCategoryRepository masterProductCategoryRepository;
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;
    // carrierRepository / carrierRateRepository / packageRepository are inherited from BaseIntegrationTest.

    @MockBean private ThumbnailRenderer thumbnailRenderer;
    @MockBean private ImageStorageService imageStorageService;
    @MockBean private ProductImageLoader productImageLoader;

    private static final String BASE = "/api/admin/master-products";
    private Long masterId;
    private Long sellerId;
    private Long categoryId;
    private Long deliveryId;
    private Long boxId;
    private Long optionId;

    @BeforeEach
    void seed() throws Exception {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        sellerId = seller.getId();
        Product product = productRepository.save(Product.builder()
                .productName("운동화").brand("나이키")
                .price(new BigDecimal("1500")).imageUrl("products/p.jpg").active(true).build());
        Category category = categoryRepository.save(Category.builder()
                .name("신발").platform("COUPANG").platformCategoryId("cat-1").build());
        categoryId = category.getId();

        // Commission (COUPANG default) + margin preset so the reused price engine resolves.
        commissionRateRepository.save(CommissionRate.builder()
                .platform("COUPANG").category(null).rate(new BigDecimal("0.10")).isDefault(true).build());
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform("COUPANG").marginRate(new BigDecimal("0.1500")).build());

        // Fresh delivery + box created in this flow (same session tenant as the listing insert). 2500 + 500
        // keep the arithmetic clean: (1500 + 2500 + 500) / 0.75 = 6000.
        Carrier carrier = carrierRepository.saveAndFlush(Carrier.builder().name("CJ").isActive(true).build());
        CarrierRate delivery = carrierRateRepository.saveAndFlush(CarrierRate.builder()
                .carrier(carrier).type("STANDARD").cost(new BigDecimal("2500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        deliveryId = delivery.getId();
        Package box = packageRepository.saveAndFlush(Package.builder()
                .type("M").cost(new BigDecimal("500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        boxId = box.getId();

        // Master + one option (BOM = the product, qty 1). Channel config (category/delivery/box) now lives on
        // the master: default delivery/box + a category for COUPANG (master × platform).
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true)
                .defaultDelivery(delivery).defaultPackage(box).build());
        masterId = master.getId();
        masterProductCategoryRepository.save(MasterProductCategory.builder()
                .masterProduct(master).platform("COUPANG").category(category).build());
        MasterProductOption option = masterProductOptionRepository.save(MasterProductOption.builder()
                .masterProduct(master).name("1세트").build());
        optionId = option.getId();
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(option).product(product).quantity(1).build());

        given(productImageLoader.load(any())).willReturn(new byte[]{1, 2, 3});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{4, 5, 6});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/generated.jpg");

        // Tenant default template (mirrors ListingAssetControllerTest: the startup-seeded default is not
        // resolved by the @Transactional test session). Renderer is mocked, so no elements needed.
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
     * Delete the listing + master graph before base cleanup removes package / carrier_rate (FK targets).
     * The master now FK-references carrier_rate/package (default delivery/box), so it must go first too.
     */
    @AfterEach
    void cleanupListingGraph() {
        generatedProductDataRepository.deleteAll();
        productListingProductRepository.deleteAll();
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
        masterProductCategoryRepository.deleteAll();
        masterProductOptionItemRepository.deleteAll();
        masterProductOptionRepository.deleteAll();
        masterProductRepository.deleteAll();
    }

    private String body() throws Exception {
        return objectMapper.writeValueAsString(ChannelAddRequest.builder()
                .sellerId(sellerId).platform("COUPANG")
                .optionIds(List.of(optionId)).build());
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void addChannel_noToken_returns401() throws Exception {
        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addChannel_userToken_returns403() throws Exception {
        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    void addChannel_adminToken_returns201WithDraftAndPrice() throws Exception {
        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.productListingId").isNumber())
                .andExpect(jsonPath("$.data.generated.optionPrices.length()").value(1))
                // (1500 + 2500 + 500) / 0.75 = 6000, rounded to nearest 10 won
                .andExpect(jsonPath("$.data.generated.optionPrices[0].sellingPrice").value(6000.00));
    }

    // ---- duplicate channel (409) ----

    @Test
    void addChannel_duplicateAccount_returns409() throws Exception {
        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
