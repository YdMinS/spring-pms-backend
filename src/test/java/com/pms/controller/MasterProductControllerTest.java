package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.PlatformCategory;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MasterProduct list + coverage matrix: authority, matrix happy path (account LEFT JOIN listing),
 * and 404 for a missing master.
 *
 * <p>Cross-tenant ownership (a tenant-2 master → 404) is covered by {@code MasterProductTenantIsolationTest}
 * (non-@Transactional): the @Transactional harness resolves one session tenant and cannot seed a second
 * tenant's row, so cross-tenant scoping is proven with the isolation-test pattern instead.</p>
 */
class MasterProductControllerTest extends BaseIntegrationTest {

    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductComponentRepository componentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;

    // Mocked so category-meta's adapter makes no live Coupang call; unstubbed returns null → empty schema (200).
    @MockBean private CoupangApiClient coupangApiClient;

    private static final String PATH = "/api/admin/master-products";
    private Long masterId;
    private Long productId1;
    private Long productId2;
    private Long categoryId;

    @BeforeEach
    void seedMatrix() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());

        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("마스터A").active(true).build());
        masterId = master.getId();

        // Two component products so option-coverage validation has a concrete target set.
        Product product1 = productRepository.save(Product.builder()
                .productName("상품1").build());
        Product product2 = productRepository.save(Product.builder()
                .productName("상품2").build());
        productId1 = product1.getId();
        productId2 = product2.getId();
        Category category = categoryRepository.save(Category.builder()
                .name("신발").platform("COUPANG").platformCategoryId("cat-1").build());
        categoryId = category.getId();
        // 52: setCategory now requires the standard category to be a leaf AND mapped to Coupang, and
        // resolvePlatformCategoryCode reads the mapping's linked PlatformCategory FK (owns code + commission).
        // The category above has no children (leaf); add the Coupang mapping + platform category so both the
        // happy-path setCategory and the category-meta resolution succeed.
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform("COUPANG").code("cat-1").name("운동화")
                .commissionRate(new java.math.BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform("COUPANG").platformCategoryId("cat-1")
                .platformCategory(platformCategory).build());
        componentRepository.save(MasterProductComponent.builder()
                .masterProduct(master).product(product1).build());
        componentRepository.save(MasterProductComponent.builder()
                .masterProduct(master).product(product2).build());

        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("X").name("리스팅").seller(seller)
                .masterProduct(master).build());
        productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(listing).optionName("기본").sellingPrice(new BigDecimal("1000")).build());

        // Two accounts: COUPANG (registered against the listing) + NAVER (uncovered) → LEFT JOIN shape.
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").accountAlias("메인")
                .vendorId("A001").accessKey("ak").secretKey("sk").isActive(true).build());
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("NAVER").accountAlias("네이버")
                .vendorId("A002").accessKey("ak").secretKey("sk").isActive(true).build());
    }

    @Test
    void matrix_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH + "/" + masterId + "/matrix"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void matrix_userToken_returns403() throws Exception {
        mockMvc.perform(get(PATH + "/" + masterId + "/matrix")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void matrix_adminToken_returnsAccountsLeftJoinedWithListing() throws Exception {
        mockMvc.perform(get(PATH + "/" + masterId + "/matrix")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.masterId").value(masterId))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                // COUPANG account is registered with a cell; NAVER account is not.
                .andExpect(jsonPath("$.data.rows[?(@.platform=='COUPANG')].registered").value(true))
                .andExpect(jsonPath("$.data.rows[?(@.platform=='COUPANG')].cell.platformProductId").value("X"))
                .andExpect(jsonPath("$.data.rows[?(@.platform=='NAVER')].registered").value(false));
    }

    @Test
    void matrix_missingMaster_returns404() throws Exception {
        mockMvc.perform(get(PATH + "/999999/matrix")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void list_adminToken_returns200() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ------------------------------------------------------------- create master authority

    @Test
    void create_noToken_returns401() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(createMasterBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createMasterBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_adminToken_returns201() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createMasterBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.components.length()").value(2));
    }

    // ------------------------------------------------------------- atomic create (master + options, 27)

    @Test
    void createMasterProduct_withOptions_returns201() throws Exception {
        String body = "{\"name\":\"원자마스터\",\"componentProductIds\":[" + productId1 + "," + productId2 + "],"
                + "\"options\":[{\"name\":\"2세트\",\"items\":["
                + "{\"productId\":" + productId1 + ",\"quantity\":2},"
                + "{\"productId\":" + productId2 + ",\"quantity\":2}]}]}";
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.components.length()").value(2))
                .andExpect(jsonPath("$.data.options.length()").value(1));
    }

    @Test
    void createMasterProduct_invalidOption_returns400() throws Exception {
        // option omits productId2 → subset of the component set; nothing is persisted (400).
        String body = "{\"name\":\"원자마스터\",\"componentProductIds\":[" + productId1 + "," + productId2 + "],"
                + "\"options\":[{\"name\":\"불완전\",\"items\":[{\"productId\":" + productId1 + ",\"quantity\":2}]}]}";
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    // ------------------------------------------------------------- create option (coverage validation)

    @Test
    void createOption_fullCoverage_returns200() throws Exception {
        String body = "{\"name\":\"2세트\",\"items\":["
                + "{\"productId\":" + productId1 + ",\"quantity\":2},"
                + "{\"productId\":" + productId2 + ",\"quantity\":2}]}";
        mockMvc.perform(post(PATH + "/" + masterId + "/options")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    // 59: per-option category-meta override round-trips through create (stored + exposed in the response).
    @Test
    void createOption_withCategoryMetaOverride_returns200_exposesMaps() throws Exception {
        String body = "{\"name\":\"30포\",\"items\":["
                + "{\"productId\":" + productId1 + ",\"quantity\":2},"
                + "{\"productId\":" + productId2 + ",\"quantity\":2}],"
                + "\"categoryAttributes\":{\"개당중량\":\"30g\"},"
                + "\"categoryNotices\":{\"용량\":\"30포\"}}";
        mockMvc.perform(post(PATH + "/" + masterId + "/options")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryAttributes.개당중량").value("30g"))
                .andExpect(jsonPath("$.data.categoryNotices.용량").value("30포"));
    }

    @Test
    void createOption_missingComponent_returns400() throws Exception {
        // items omit productId2 → subset of the component set
        String body = "{\"name\":\"불완전\",\"items\":[{\"productId\":" + productId1 + ",\"quantity\":1}]}";
        mockMvc.perform(post(PATH + "/" + masterId + "/options")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void update_resendSameComponents_returns200_noUniqueViolation() throws Exception {
        // Regression: PATCH re-sending an unchanged component set does delete + re-insert of the same
        // (master, product) pairs in one transaction. Without a flush between them, Hibernate runs the
        // INSERTs before the entity DELETEs and hits the UQ_MPC unique index (was a 500).
        String body = "{\"componentProductIds\":[" + productId1 + "," + productId2 + "]}";
        mockMvc.perform(patch(PATH + "/" + masterId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.components.length()").value(2));
    }

    // ------------------------------------------------------------- standard category (single, 44)

    @Test
    void setCategory_noToken_returns401() throws Exception {
        mockMvc.perform(put(PATH + "/" + masterId + "/category")
                        .contentType(MediaType.APPLICATION_JSON).content(categoryBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setCategory_userToken_returns403() throws Exception {
        mockMvc.perform(put(PATH + "/" + masterId + "/category")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(categoryBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void setCategory_thenGet_adminToken_returns200() throws Exception {
        mockMvc.perform(put(PATH + "/" + masterId + "/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(categoryBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").value(categoryId))
                .andExpect(jsonPath("$.data.categoryName").value("신발"));

        mockMvc.perform(get(PATH + "/" + masterId + "/category")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").value(categoryId));
    }

    @Test
    void setCategory_missingMaster_returns404() throws Exception {
        mockMvc.perform(put(PATH + "/999999/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(categoryBody()))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearCategory_adminToken_returns204_thenGetIsNull() throws Exception {
        mockMvc.perform(put(PATH + "/" + masterId + "/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(categoryBody()))
                .andExpect(status().isOk());

        mockMvc.perform(delete(PATH + "/" + masterId + "/category")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(PATH + "/" + masterId + "/category")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").doesNotExist());
    }

    // ------------------------------------------------------------- tags (33)

    @Test
    void updateTags_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + masterId + "/tags")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"신상\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateTags_adminToken_returns200Deduped() throws Exception {
        // Duplicate "신상" collapses (order-preserving dedup) → 2 tags exposed as current value.
        mockMvc.perform(patch(PATH + "/" + masterId + "/tags")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"신상\",\"신상\",\"봄\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.tags.length()").value(2))
                .andExpect(jsonPath("$.data.tags[0]").value("신상"))
                .andExpect(jsonPath("$.data.tags[1]").value("봄"));
    }

    // ------------------------------------------------------------- 옵션확인 suffix (69)

    @Test
    void updateRegistrationNameSuffix_adminToken_savesReplaceValues_blankToNull() throws Exception {
        mockMvc.perform(put(PATH + "/" + masterId + "/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false, "suffix", "옵션참고"))))
                .andExpect(status().isOk());
        MasterProduct saved = masterProductRepository.findById(masterId).orElseThrow();
        assertThat(saved.getOptionCheckSuffixEnabled()).isFalse();
        assertThat(saved.getOptionCheckSuffix()).isEqualTo("옵션참고");

        // Replace with a blank suffix (enabled omitted) → suffix normalized to null (inherit).
        mockMvc.perform(put(PATH + "/" + masterId + "/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Collections.singletonMap("suffix", "  "))))
                .andExpect(status().isOk());
        assertThat(masterProductRepository.findById(masterId).orElseThrow().getOptionCheckSuffix()).isNull();
    }

    // ------------------------------------------------------------- shipping override (75)

    @Test
    void updateShippingOverride_userToken_returns403() throws Exception {
        mockMvc.perform(patch(PATH + "/" + masterId + "/shipping-override")
                        .header("Authorization", "Bearer " + userToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"override\":{\"deliveryMethod\":\"MAKE_ORDER\"}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateShippingOverride_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + masterId + "/shipping-override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"override\":{\"deliveryMethod\":\"MAKE_ORDER\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateShippingOverride_adminToken_savesAndPrefills() throws Exception {
        mockMvc.perform(patch(PATH + "/" + masterId + "/shipping-override")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "override", Map.of("deliveryMethod", "MAKE_ORDER", "deliveryCharge", "3000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.shippingOverride.deliveryMethod").value("MAKE_ORDER"))
                .andExpect(jsonPath("$.data.shippingOverride.deliveryCharge").value("3000"));
        Map<String, String> saved = masterProductRepository.findById(masterId).orElseThrow().getShippingOverride();
        assertThat(saved).containsEntry("deliveryMethod", "MAKE_ORDER").containsEntry("deliveryCharge", "3000");
    }

    // MUST-KEEP (level constraint): a place key on the MASTER override is silently dropped (channel-level only).
    @Test
    void updateShippingOverride_placeKeyOnMaster_silentlyDropped() throws Exception {
        mockMvc.perform(patch(PATH + "/" + masterId + "/shipping-override")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "override", Map.of("outboundShippingPlaceCode", "OUT-9",
                                        "deliveryMethod", "MAKE_ORDER")))))
                .andExpect(status().isOk());
        Map<String, String> saved = masterProductRepository.findById(masterId).orElseThrow().getShippingOverride();
        assertThat(saved).containsEntry("deliveryMethod", "MAKE_ORDER")
                .doesNotContainKey("outboundShippingPlaceCode");   // place key dropped, no 400
    }

    @Test
    void updateShippingOverride_missingMaster_returns404() throws Exception {
        mockMvc.perform(patch(PATH + "/999999/shipping-override")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"override\":{\"deliveryMethod\":\"MAKE_ORDER\"}}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- force apply to channels (77)

    @Test
    void applyShippingOverrideToChannels_adminToken_returnsAffectedCount() throws Exception {
        mockMvc.perform(post(PATH + "/" + masterId + "/shipping-override/apply-to-channels")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.affectedChannels").exists());
    }

    @Test
    void applyShippingOverrideToChannels_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH + "/" + masterId + "/shipping-override/apply-to-channels")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void applyShippingOverrideToChannels_noToken_returns401() throws Exception {
        mockMvc.perform(post(PATH + "/" + masterId + "/shipping-override/apply-to-channels"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void applyShippingOverrideToChannels_missingMaster_returns404() throws Exception {
        mockMvc.perform(post(PATH + "/999999/shipping-override/apply-to-channels")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void applyShippingOverrideToChannels_emptyListingIds_appliesToAll() throws Exception {
        // 79: the body is optional and an empty selection keeps the original "every channel" behaviour
        mockMvc.perform(post(PATH + "/" + masterId + "/shipping-override/apply-to-channels")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedChannels").exists());
    }

    @Test
    void applyShippingOverrideToChannels_listingIdOutsideMaster_returns400() throws Exception {
        mockMvc.perform(post(PATH + "/" + masterId + "/shipping-override/apply-to-channels")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingIds\":[999999]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRegistrationNameSuffix_userToken_returns403() throws Exception {
        mockMvc.perform(put(PATH + "/" + masterId + "/registration-name-suffix")
                        .header("Authorization", "Bearer " + userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "suffix", "옵션확인"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRegistrationNameSuffix_missingMaster_returns404() throws Exception {
        mockMvc.perform(put(PATH + "/999999/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "suffix", "옵션확인"))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- category meta (47)

    /**
     * Set the master's standard category so resolvePlatformCategoryCode succeeds. The COUPANG mapping (with a
     * linked PlatformCategory FK) is already created in {@code @BeforeEach} — adding another here would violate
     * UNIQUE(category_id, platform).
     */
    private void prepareMeta() {
        Category category = categoryRepository.findById(categoryId).orElseThrow();
        MasterProduct master = masterProductRepository.findById(masterId).orElseThrow();
        masterProductRepository.save(master.toBuilder().category(category).build());
    }

    @Test
    void categoryMeta_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH + "/" + masterId + "/category-meta?platform=COUPANG"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void categoryMeta_userToken_returns403() throws Exception {
        mockMvc.perform(get(PATH + "/" + masterId + "/category-meta?platform=COUPANG")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void categoryMeta_adminToken_returns200_emptySchemaTolerated() throws Exception {
        prepareMeta();
        // The mocked client returns null → the adapter tolerates it as an empty schema → still 200.
        mockMvc.perform(get(PATH + "/" + masterId + "/category-meta?platform=COUPANG")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.attributes").isArray())
                .andExpect(jsonPath("$.data.values").exists());
    }

    @Test
    void categoryMeta_missingMaster_returns404() throws Exception {
        mockMvc.perform(get(PATH + "/999999/category-meta?platform=COUPANG")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryAttributes_noToken_returns401() throws Exception {
        mockMvc.perform(patch(PATH + "/" + masterId + "/category-attributes")
                        .contentType(MediaType.APPLICATION_JSON).content(attributesBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void categoryAttributes_userToken_returns403() throws Exception {
        mockMvc.perform(patch(PATH + "/" + masterId + "/category-attributes")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(attributesBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void categoryAttributes_adminToken_returns200() throws Exception {
        mockMvc.perform(patch(PATH + "/" + masterId + "/category-attributes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(attributesBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void categoryAttributes_missingMaster_returns404() throws Exception {
        mockMvc.perform(patch(PATH + "/999999/category-attributes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(attributesBody()))
                .andExpect(status().isNotFound());
    }

    private String attributesBody() {
        return "{\"attributes\":{\"원산지\":\"국내산\"},\"notices\":{}}";
    }

    private String categoryBody() {
        return "{\"categoryId\":" + categoryId + "}";
    }

    private String createMasterBody() {
        return "{\"name\":\"신규마스터\",\"componentProductIds\":[" + productId1 + "," + productId2 + "]}";
    }
}
