package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.CommissionRate;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProduct;
import com.pms.domain.Package;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.MarginPolicyRepository;
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
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    // carrierRepository / carrierRateRepository / packageRepository are inherited from BaseIntegrationTest.

    @MockBean private ThumbnailRenderer thumbnailRenderer;
    @MockBean private ImageStorageService imageStorageService;
    @MockBean private ProductImageLoader productImageLoader;

    private static final String PATH = "/api/admin/product-listings";
    private Long listingId;
    private Long masterId;

    @BeforeEach
    void seedCell() throws Exception {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        Product product = productRepository.save(Product.builder()
                .productName("운동화").brand("나이키")
                .price(new BigDecimal("1500")).imageUrl("products/p.jpg").active(true).build());

        // Commission is now owned by the mapped PlatformCategory (52); the legacy CommissionRate is kept but
        // unused by the price engine. Margin preset so the price engine resolves.
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

        // Channel config lives on the master (13/44): a single standard category + default delivery/box drive
        // the price engine; the platform code comes from a CategoryMapping. The cell's own delivery/package
        // columns are deprecated (kept null here).
        Category category = categoryRepository.save(Category.builder().name("신발").build());
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true).category(category)
                .defaultDelivery(delivery).defaultPackage(box).build());
        masterId = master.getId();
        // 52: the mapped PlatformCategory owns the mall code + commission (0.10 = the old rate → same price).
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform("COUPANG").code("cat-1").name("운동화")
                .commissionRate(new BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform("COUPANG").platformCategoryId("cat-1")
                .platformCategory(platformCategory).build());

        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("X").name("셀").seller(seller)
                .masterProduct(master)
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

        // Likewise create the tenant default DETAIL template in-session: ChannelTemplateResolver (21) now
        // throws when neither an account override nor a tenant default exists, and the startup-seeded default
        // lives under tenant 1 (invisible to this @Transactional session). POST returns 200.
        String detailTemplateJson = objectMapper.writeValueAsString(java.util.Map.of(
                "name", "쿠팡 상세 기본", "active", true, "isDefault", true,
                "blocks", java.util.List.of()));
        mockMvc.perform(post("/api/admin/detail-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(detailTemplateJson))
                .andExpect(status().isOk());
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
        // The master FK-references carrier_rate/package (default delivery/box) — remove it before base cleanup.
        categoryMappingRepository.deleteAll();
        masterProductRepository.deleteAll();
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
                // option name surfaced so the matrix shows the label without cross-referencing master ids
                .andExpect(jsonPath("$.data.optionPrices[0].optionName").value("기본"))
                // (1500 + 2500 + 500) / 0.75 = 6000, rounded to nearest 10 won
                .andExpect(jsonPath("$.data.optionPrices[0].sellingPrice").value(6000.00))
                // per-channel active flag (42) surfaced so the matrix can render inline (43)
                .andExpect(jsonPath("$.data.optionPrices[0].active").value(true))
                // 87: option-axis market flag so the front can lock the checkbox (DRAFT cell → not on the market)
                .andExpect(jsonPath("$.data.optionPrices[0].onMarket").value(false));
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

    // ---- resolved detail template (29): authority + shape + 404 ----

    @Test
    void resolvedDetailTemplate_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH + "/" + listingId + "/detail-template"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolvedDetailTemplate_userToken_returns403() throws Exception {
        mockMvc.perform(get(PATH + "/" + listingId + "/detail-template")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolvedDetailTemplate_adminToken_returns200WithBlocks() throws Exception {
        // Falls back to the tenant default detail template seeded in-session (no account override).
        mockMvc.perform(get(PATH + "/" + listingId + "/detail-template")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("쿠팡 상세 기본"))
                .andExpect(jsonPath("$.data.blocks").exists());
    }

    @Test
    void resolvedDetailTemplate_missingCell_returns404() throws Exception {
        mockMvc.perform(get(PATH + "/999999/detail-template")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
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

    // ---- tags (33): channel raw tags reflected on the cell (auth covered by the global /api/admin/** handler) ----

    @Test
    void updateTags_adminToken_returns200Deduped() throws Exception {
        // Duplicate "인기" collapses (order-preserving dedup); no regenerate needed (cell not yet generated).
        mockMvc.perform(patch(PATH + "/" + listingId + "/tags")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"tags\":[\"인기\",\"인기\",\"세일\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productListingId").value(listingId))
                .andExpect(jsonPath("$.data.tags.length()").value(2))
                .andExpect(jsonPath("$.data.tags[0]").value("인기"))
                .andExpect(jsonPath("$.data.tags[1]").value("세일"));
    }

    // ---- display name (35): 노출상품명 = listing name, internal only (no regenerate/push) ----

    @Test
    void updateDisplayName_adminToken_returns200AndReflectsName() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/name")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"name\":\"  행복상회 특가  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(productListingRepository.findById(listingId).orElseThrow().getName())
                .isEqualTo("행복상회 특가");
    }

    @Test
    void updateDisplayName_blank_returns400() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/name")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateDisplayName_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/name")
                        .contentType("application/json").content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateDisplayName_userToken_returns403() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/name")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateDisplayName_missingId_returns404() throws Exception {
        mockMvc.perform(patch(PATH + "/999999/name")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- shipping override (75): channel-level overrides reflected on the cell (no regenerate/push) ----

    @Test
    void updateShippingOverride_adminToken_returns200AndReflectsOverride() throws Exception {
        // Listing whitelist includes place keys (channel level) — a place key is kept here (unlike the master).
        mockMvc.perform(patch(PATH + "/" + listingId + "/shipping-override")
                        .header("Authorization", "Bearer " + adminToken).contentType("application/json")
                        .content("{\"override\":{\"outboundShippingPlaceCode\":\"OUT-9\",\"deliveryCharge\":\"3000\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productListingId").value(listingId))
                .andExpect(jsonPath("$.data.shippingOverride.outboundShippingPlaceCode").value("OUT-9"))
                .andExpect(jsonPath("$.data.shippingOverride.deliveryCharge").value("3000"));
        assertThat(productListingRepository.findById(listingId).orElseThrow().getShippingOverride())
                .containsEntry("outboundShippingPlaceCode", "OUT-9");
    }

    @Test
    void updateShippingOverride_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/shipping-override")
                        .contentType("application/json").content("{\"override\":{\"deliveryCharge\":\"3000\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateShippingOverride_userToken_returns403() throws Exception {
        mockMvc.perform(patch(PATH + "/" + listingId + "/shipping-override")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content("{\"override\":{\"deliveryCharge\":\"3000\"}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateShippingOverride_missingCell_returns404() throws Exception {
        mockMvc.perform(patch(PATH + "/999999/shipping-override")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"override\":{\"deliveryCharge\":\"3000\"}}"))
                .andExpect(status().isNotFound());
    }

    // ---- shippingReady flag (77): the register guard exposed as a read flag for the [마켓 등록] button ----

    @Test
    void getGenerated_shippingComplete_returnsShippingReadyTrue() throws Exception {
        // Given every register-required shipping field set at the channel level (no account config needed)
        java.util.Map<String, String> complete = new java.util.LinkedHashMap<>();
        complete.put("outboundShippingPlaceCode", "OUT-1");
        complete.put("returnCenterCode", "RC-1");
        complete.put("returnChargeName", "반품담당");
        complete.put("returnContactNumber", "021234567");
        complete.put("returnZipCode", "06000");
        complete.put("returnAddress", "서울시");
        complete.put("returnAddressDetail", "1층");
        complete.put("returnCharge", "2500");
        complete.put("deliveryChargeOnReturn", "2500");
        complete.put("deliveryMethod", "SEQUENCIAL");
        complete.put("deliveryCompanyCode", "KGB");
        complete.put("deliveryChargeType", "FREE");
        complete.put("deliveryCharge", "0");
        complete.put("remoteAreaDeliverable", "N");
        complete.put("unionDeliveryType", "NOT_UNION_DELIVERY");
        ProductListing cell = productListingRepository.findById(listingId).orElseThrow();
        productListingRepository.save(cell.toBuilder().shippingOverride(complete).build());

        mockMvc.perform(post(PATH + "/" + listingId + "/regenerate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(PATH + "/" + listingId + "/generated")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingReady").value(true));
    }

    @Test
    void getGenerated_noShippingAnywhere_returnsShippingReadyFalse() throws Exception {
        // Given no account config, no master override and no channel override (the base fixture state)
        mockMvc.perform(post(PATH + "/" + listingId + "/regenerate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(PATH + "/" + listingId + "/generated")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingReady").value(false));
    }

    // ---- inherited shipping baseline (76): master ?? account, own channel override excluded ----

    @Test
    void resolveInheritedShipping_adminToken_inheritsMasterExcludesOwn() throws Exception {
        // master override (all-channels) + this listing's own override; the inherited baseline drops the latter.
        MasterProduct master = masterProductRepository.findById(masterId).orElseThrow();
        masterProductRepository.save(master.toBuilder()
                .shippingOverride(java.util.Map.of("deliveryMethod", "MAKE_ORDER")).build());
        ProductListing cell = productListingRepository.findById(listingId).orElseThrow();
        productListingRepository.save(cell.toBuilder()
                .shippingOverride(java.util.Map.of("deliveryCharge", "3000")).build());

        mockMvc.perform(get(PATH + "/" + listingId + "/shipping-inherited")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.deliveryMethod").value("MAKE_ORDER")) // from master
                .andExpect(jsonPath("$.data.deliveryCharge").value(org.hamcrest.Matchers.nullValue())); // own excluded
    }

    @Test
    void resolveInheritedShipping_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH + "/" + listingId + "/shipping-inherited"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolveInheritedShipping_userToken_returns403() throws Exception {
        mockMvc.perform(get(PATH + "/" + listingId + "/shipping-inherited")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolveInheritedShipping_missingCell_returns404() throws Exception {
        mockMvc.perform(get(PATH + "/999999/shipping-inherited")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ---- thumbnail override / clear (25): authority + happy path + empty file 400 ----

    /** Valid minimal JPEG (magic bytes FF D8 FF...) so the real ImageValidator passes. */
    private MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "t.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0});
    }

    private void regenerate() throws Exception {
        mockMvc.perform(post(PATH + "/" + listingId + "/regenerate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void overrideThumbnail_noToken_returns401() throws Exception {
        mockMvc.perform(multipart(PATH + "/" + listingId + "/thumbnail").file(jpeg()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void overrideThumbnail_userToken_returns403() throws Exception {
        mockMvc.perform(multipart(PATH + "/" + listingId + "/thumbnail").file(jpeg())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void overrideThumbnail_adminToken_returns200WithManualOverride() throws Exception {
        regenerate();                       // the cell must be generated first
        mockMvc.perform(multipart(PATH + "/" + listingId + "/thumbnail").file(jpeg())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.thumbnailUrl").value("thumbnails/generated.jpg"))
                .andExpect(jsonPath("$.data.thumbnailSource").value("MANUAL_OVERRIDE"));
    }

    @Test
    void overrideThumbnail_emptyFile_returns400() throws Exception {
        regenerate();
        MockMultipartFile empty = new MockMultipartFile("file", "t.jpg", "image/jpeg", new byte[0]);
        mockMvc.perform(multipart(PATH + "/" + listingId + "/thumbnail").file(empty)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clearThumbnail_noToken_returns401() throws Exception {
        mockMvc.perform(delete(PATH + "/" + listingId + "/thumbnail"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clearThumbnail_userToken_returns403() throws Exception {
        mockMvc.perform(delete(PATH + "/" + listingId + "/thumbnail")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void clearThumbnail_adminToken_returns200WithAuto() throws Exception {
        regenerate();
        // Put an override in place, then drop it back to AUTO.
        mockMvc.perform(multipart(PATH + "/" + listingId + "/thumbnail").file(jpeg())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete(PATH + "/" + listingId + "/thumbnail")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.thumbnailSource").value("AUTO"));
    }
}
