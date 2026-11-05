package com.pms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The master side of a product merge against a real database (FEATURE_2611_09).
 *
 * <p>Seed — target A, source B, partner C:</p>
 * <ul>
 *   <li>「견과 세트」 = {B, C}, option 「1+1」 (B×1, C×1), one on-market listing → stays unique as {A, C}:
 *       its rows are rewritten in place (D3) and the listing gets [수정 요청] (D7).</li>
 *   <li>「버릴 단품」 = {B}, options 「1개」 (B×1) · 「2개 묶음」 (B×2), an on-market listing (two linked options +
 *       one channel-only option) and a draft listing → would equal 「남길 단품」: deleted, listings move (D5 ·
 *       D16 · D17 · D20).</li>
 *   <li>「남길 단품」 = {A}, option 「1개」 (A×1), one draft listing → receives the listings.</li>
 * </ul>
 */
class ProductMergeMasterLinkIntegrationTest extends BaseIntegrationTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductComponentRepository componentRepository;
    @Autowired private MasterProductOptionRepository optionRepository;
    @Autowired private MasterProductOptionItemRepository optionItemRepository;
    @Autowired private ProductListingRepository listingRepository;
    @Autowired private ProductListingOptionRepository listingOptionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private MarginPolicyRepository marginPolicyRepository;

    private Category category;

    private Product target;
    private Product source;
    private Product partner;
    private Seller seller;

    private MasterProduct setMaster;
    private MasterProductComponent setSourceComponent;
    private MasterProductOptionItem setSourceItem;
    private ProductListing setListing;

    private MasterProduct deleteMaster;
    private MasterProductOption deleteOne;
    private MasterProductOption deleteTwo;
    private ProductListing marketListing;
    private ProductListingOption marketOne;
    private ProductListingOption marketTwo;
    private ProductListingOption marketChannelOnly;
    private ProductListing draftListing;

    private MasterProduct keepMaster;
    private MasterProductOption keepOne;
    private ProductListing keepListing;

    @BeforeEach
    void seed() {
        target = productRepository.save(Product.builder().productName("남길 물품 A").active(true).build());
        source = productRepository.save(Product.builder().productName("버릴 물품 B").active(true).build());
        partner = productRepository.save(Product.builder().productName("짝 물품 C").active(true).build());
        seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("쿠팡 본계정").isActive(true).build());
        // The merge re-prices the moved and rewritten listings (D7 · D21), so every master is a priceable one:
        // a mapped leaf category, a delivery and a box, and a Coupang margin for the seller.
        category = categoryRepository.save(Category.builder()
                .name("견과").platform(Platform.COUPANG).platformCategoryId("cat-1").build());
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("cat-1").name("견과류")
                .commissionRate(new BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform(Platform.COUPANG).platformCategoryId("cat-1")
                .platformCategory(platformCategory).build());
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform(Platform.COUPANG).marginRate(new BigDecimal("0.1500")).build());

        setMaster = master("견과 세트");
        setSourceComponent = component(setMaster, source);
        component(setMaster, partner);
        MasterProductOption setOption = option(setMaster, "1+1");
        setSourceItem = item(setOption, source, 1);
        item(setOption, partner, 1);
        setListing = listing(setMaster, "세트 판매상품", "P-SET");
        listingOption(setListing, "1+1", setOption);

        deleteMaster = master("버릴 단품");
        component(deleteMaster, source);
        deleteOne = option(deleteMaster, "1개");
        item(deleteOne, source, 1);
        deleteTwo = option(deleteMaster, "2개 묶음");
        item(deleteTwo, source, 2);
        marketListing = listing(deleteMaster, "버릴 단품 쿠팡", "P-SINGLE");
        marketOne = listingOption(marketListing, "1개", deleteOne);
        marketTwo = listingOption(marketListing, "2개 묶음", deleteTwo);
        marketChannelOnly = listingOption(marketListing, "채널 전용", null);
        draftListing = listing(deleteMaster, "버릴 단품 초안", null);
        listingOption(draftListing, "1개", deleteOne);

        keepMaster = master("남길 단품");
        component(keepMaster, target);
        keepOne = option(keepMaster, "1개");
        item(keepOne, target, 1);
        keepListing = listing(keepMaster, "남길 단품 초안", null);
        listingOption(keepListing, "1개", keepOne);
    }

    /**
     * The masters carry delivery/box FKs and the base teardown deletes those rows — unlink first (JUnit runs a
     * subclass {@code @AfterEach} before {@code tearDownBase}), same as {@code MasterProductControllerTest}.
     */
    @AfterEach
    void unlinkMasterDefaults() {
        masterProductRepository.findAll().forEach(master -> masterProductRepository.save(
                master.toBuilder().defaultDelivery(null).defaultPackage(null).build()));
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private MasterProduct master(String name) {
        return masterProductRepository.save(MasterProduct.builder().name(name).active(true)
                .category(category)
                .defaultDelivery(carrierRateRepository.findById(seededCarrierRateId).orElseThrow())
                .defaultPackage(packageRepository.findById(seededPackageId).orElseThrow())
                .build());
    }

    private MasterProductComponent component(MasterProduct master, Product product) {
        return componentRepository.save(MasterProductComponent.builder().masterProduct(master).product(product).build());
    }

    private MasterProductOption option(MasterProduct master, String name) {
        return optionRepository.save(MasterProductOption.builder().masterProduct(master).name(name).build());
    }

    private MasterProductOptionItem item(MasterProductOption option, Product product, int quantity) {
        return optionItemRepository.save(MasterProductOptionItem.builder()
                .option(option).product(product).quantity(quantity).build());
    }

    private ProductListing listing(MasterProduct master, String name, String platformProductId) {
        return listingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).name(name)
                .status(platformProductId == null ? ListingStatus.DRAFT : ListingStatus.SELLING)
                .platformProductId(platformProductId).seller(seller).masterProduct(master).build());
    }

    private ProductListingOption listingOption(ProductListing listing, String name, MasterProductOption masterOption) {
        return listingOptionRepository.save(ProductListingOption.builder()
                .productListing(listing).optionName(name).masterProductOption(masterOption)
                .sellingPrice(new BigDecimal("6000")).build());
    }

    private String previewBody() {
        return """
                {"targetProductId": %d, "sourceProductId": %d}
                """.formatted(target.getId(), source.getId());
    }

    /** {@code choices} = the JSON array of {@code masterOptionChoices}. */
    private String mergeBody(String choices) {
        return """
                {
                  "targetProductId": %d,
                  "sourceProductId": %d,
                  "fields": {},
                  "transfer": {
                    "purchaseRecords": true, "stockMovements": true, "shipmentItems": true,
                    "images": true, "shoppingListItems": true, "priceChangeLogs": true,
                    "appendMemo": true
                  },
                  "masterOptionChoices": %s
                }
                """.formatted(target.getId(), source.getId(), choices);
    }

    /** 「1개」 → the surviving 「1개」, 「2개 묶음」 → a new option of that name. */
    private String validChoices() {
        return """
                [
                  {"sourceOptionId": %d, "targetOptionId": %d},
                  {"sourceOptionId": %d, "newOptionName": " 2개 묶음 "}
                ]
                """.formatted(deleteOne.getId(), keepOne.getId(), deleteTwo.getId());
    }

    private org.springframework.test.web.servlet.ResultActions merge(String choices) throws Exception {
        return mockMvc.perform(post("/api/products/merge")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(mergeBody(choices)));
    }

    private String label(MasterProduct master) {
        return master.getName() + "(#" + master.getId() + ")";
    }

    /** Nothing of the master side moved: B is still in its masters and still active. */
    private void assertNothingMoved() {
        assertThat(componentRepository.findById(setSourceComponent.getId()).orElseThrow().getProduct().getId())
                .isEqualTo(source.getId());
        assertThat(masterProductRepository.findById(deleteMaster.getId())).isPresent();
        assertThat(listingRepository.findById(marketListing.getId()).orElseThrow().getMasterProduct().getId())
                .isEqualTo(deleteMaster.getId());
        assertThat(productRepository.findById(source.getId()).orElseThrow().getActive()).isTrue();
    }

    // ---- preview (D28) -------------------------------------------------------------------------

    @Test
    void testPreviewListsTheDeletedMasterItsListingsAndDefaults() throws Exception {
        mockMvc.perform(post("/api/products/merge/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pairs.length()").value(1))
                .andExpect(jsonPath("$.data.pairs[0].deleteMasterId").value(deleteMaster.getId()))
                .andExpect(jsonPath("$.data.pairs[0].deleteMasterName").value("버릴 단품"))
                .andExpect(jsonPath("$.data.pairs[0].keepMasterId").value(keepMaster.getId()))
                .andExpect(jsonPath("$.data.pairs[0].keepMasterName").value("남길 단품"))
                .andExpect(jsonPath("$.data.pairs[0].listings.length()").value(2))
                .andExpect(jsonPath("$.data.pairs[0].listings[0].listingId").value(marketListing.getId()))
                .andExpect(jsonPath("$.data.pairs[0].listings[1].listingId").value(draftListing.getId()))
                .andExpect(jsonPath("$.data.pairs[0].keepOptions.length()").value(1))
                .andExpect(jsonPath("$.data.pairs[0].keepOptions[0].name").value("1개"))
                .andExpect(jsonPath("$.data.pairs[0].keepOptions[0].items[0].productName").value("남길 물품 A"))
                .andExpect(jsonPath("$.data.pairs[0].optionRows.length()").value(2))
                // D24 ②: same quantities + same name → that option
                .andExpect(jsonPath("$.data.pairs[0].optionRows[0].sourceOptionName").value("1개"))
                .andExpect(jsonPath("$.data.pairs[0].optionRows[0].defaultOptionId").value(keepOne.getId()))
                // D24 ④: the quantities already carry the kept product
                .andExpect(jsonPath("$.data.pairs[0].optionRows[1].sourceOptionName").value("2개 묶음"))
                .andExpect(jsonPath("$.data.pairs[0].optionRows[1].items[0].productId").value(target.getId()))
                .andExpect(jsonPath("$.data.pairs[0].optionRows[1].items[0].productName").value("남길 물품 A"))
                .andExpect(jsonPath("$.data.pairs[0].optionRows[1].items[0].quantity").value(2))
                // D24 ② · ③: no option with the same quantities → null = 「새 옵션 만들기」
                .andExpect(jsonPath("$.data.pairs[0].optionRows[1].defaultOptionId").doesNotExist());
    }

    // ---- merge (D1 · D3 · D5 · D7 · D11 · D16 ~ D26) ----------------------------------------------

    @Test
    void testMergeMovesMasterLinksAndListings() throws Exception {
        MvcResult result = merge(validChoices())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.moved.masterProducts").value(2))
                .andExpect(jsonPath("$.data.changedMasterIds.length()").value(2))
                .andExpect(jsonPath("$.data.changedMasterIds[0]").value(setMaster.getId()))
                .andExpect(jsonPath("$.data.changedMasterIds[1]").value(keepMaster.getId()))
                .andReturn();

        // D3: the set master's rows are rewritten in place — same row ids, same quantity, the kept product
        MasterProductComponent component = componentRepository.findById(setSourceComponent.getId()).orElseThrow();
        assertThat(component.getProduct().getId()).isEqualTo(target.getId());
        MasterProductOptionItem item = optionItemRepository.findById(setSourceItem.getId()).orElseThrow();
        assertThat(item.getProduct().getId()).isEqualTo(target.getId());
        assertThat(item.getQuantity()).isEqualTo(1);
        // D7: the on-market listing carrying the option gets [수정 요청]
        assertThat(listingRepository.findById(setListing.getId()).orElseThrow().isNeedsMarketSync()).isTrue();

        // D20: the master whose set became 「남길 단품」's is gone
        assertThat(masterProductRepository.findById(deleteMaster.getId())).isEmpty();
        // D16 · D17: both listings — on a market or not — now hang under 「남길 단품」
        ProductListing movedMarket = listingRepository.findById(marketListing.getId()).orElseThrow();
        ProductListing movedDraft = listingRepository.findById(draftListing.getId()).orElseThrow();
        assertThat(movedMarket.getMasterProduct().getId()).isEqualTo(keepMaster.getId());
        assertThat(movedDraft.getMasterProduct().getId()).isEqualTo(keepMaster.getId());
        // D21: on a market → [수정 요청]; a draft is never flagged
        assertThat(movedMarket.isNeedsMarketSync()).isTrue();
        assertThat(movedDraft.isNeedsMarketSync()).isFalse();

        // D23 · D24: 「1개」 → the chosen option; 「2개 묶음」 → a new option of the trimmed name with A×2
        MasterProductOption created = optionRepository.findByMasterProductId(keepMaster.getId()).stream()
                .filter(o -> "2개 묶음".equals(o.getName())).findFirst().orElseThrow();
        assertThat(optionItemRepository.findByOptionId(created.getId()))
                .extracting(i -> i.getProduct().getId(), MasterProductOptionItem::getQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(target.getId(), 2));
        assertThat(listingOptionRepository.findById(marketOne.getId()).orElseThrow()
                .getMasterProductOption().getId()).isEqualTo(keepOne.getId());
        assertThat(listingOptionRepository.findById(marketTwo.getId()).orElseThrow()
                .getMasterProductOption().getId()).isEqualTo(created.getId());
        // D26: the channel-only option stays channel-only
        assertThat(listingOptionRepository.findById(marketChannelOnly.getId()).orElseThrow()
                .getMasterProductOption()).isNull();
        // D25: the surviving master's own listing gets the new option, switched off
        Map<Long, Boolean> keepListingOptions = listingOptionRepository.findByProductListingId(keepListing.getId())
                .stream().filter(o -> o.getMasterProductOption() != null)
                .collect(Collectors.toMap(o -> o.getMasterProductOption().getId(), ProductListingOption::getActive));
        assertThat(keepListingOptions).containsEntry(created.getId(), false);

        // D9: the source went through the guarded delete; D11: the note counts the masters
        assertThat(productRepository.findById(source.getId()).orElseThrow().getActive()).isFalse();
        assertThat(componentRepository.findByProductId(source.getId())).isEmpty();
        assertThat(productRepository.findById(target.getId()).orElseThrow().getDescription()).contains("마스터 2개");

        // D22: the snapshot keeps the deleted master and where the listings came from
        String fileName = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("snapshotFileName").asText();
        JsonNode links = objectMapper.readTree(Files.readString(Path.of("build/test-merge-snapshots", fileName)))
                .path("masterLinks");
        assertThat(links.path("deletedMasters").get(0).path("masterProductId").asLong())
                .isEqualTo(deleteMaster.getId());
        assertThat(links.path("deletedMasters").get(0).path("master_product_option").size()).isEqualTo(2);
        assertThat(links.path("deletedMasters").get(0).path("master_product_option_item").size()).isEqualTo(2);
        assertThat(links.path("movedListings").size()).isEqualTo(2);
        assertThat(links.path("movedListings").get(0).path("productListingId").asLong())
                .isEqualTo(marketListing.getId());
        assertThat(links.path("movedListings").get(0).path("options").size()).isEqualTo(3);
    }

    // ---- refusals, all before anything moves (D6) ---------------------------------------------------

    @Test
    void testMasterHoldingBothProductsBlocksMergeAndPreview() throws Exception {
        MasterProduct both = master("함께 세트");
        component(both, target);
        component(both, source);
        String message = "남길 물품과 버릴 물품이 함께 들어 있는 마스터 상품이 있습니다: " + label(both)
                + ". 마스터 구성 편집에서 한쪽을 먼저 빼 주세요.";

        merge(validChoices())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message));
        mockMvc.perform(post("/api/products/merge/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message));
        assertNothingMoved();
    }

    @Test
    void testMasterWithSourceOnlyInOptionQuantitiesBlocksMergeAndPreview() throws Exception {
        // FEATURE_2611_13 D2: B sits in an option's quantities but not in the component list (drifted data saved
        // past the service guard). No listing option points at it — the merge is refused all the same.
        MasterProduct drifted = master("어긋난 세트");
        component(drifted, partner);
        MasterProductOption driftedOption = option(drifted, "1+1");
        item(driftedOption, partner, 1);
        item(driftedOption, source, 1);
        String message = "구성품 목록에는 없고 옵션 수량에만 버릴 물품이 남아 있는 마스터 상품이 있습니다: "
                + label(drifted) + ". 마스터 구성 편집에서 정리해 주세요.";

        merge(validChoices())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message));
        mockMvc.perform(post("/api/products/merge/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message));
        assertNothingMoved();
    }

    @Test
    void testMissingOptionChoiceIs400() throws Exception {
        merge("""
                [{"sourceOptionId": %d, "targetOptionId": %d}]
                """.formatted(deleteOne.getId(), keepOne.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "옵션 고르기가 빠졌습니다: " + label(deleteMaster) + " 「2개 묶음」"));
        assertNothingMoved();
    }

    @Test
    void testOptionOutsideTheSurvivingMasterIs400() throws Exception {
        MasterProductOption foreign = optionRepository.findByMasterProductId(setMaster.getId()).get(0);
        merge("""
                [
                  {"sourceOptionId": %d, "targetOptionId": %d},
                  {"sourceOptionId": %d, "newOptionName": "2개 묶음"}
                ]
                """.formatted(deleteOne.getId(), foreign.getId(), deleteTwo.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "고른 옵션이 남는 마스터 " + label(keepMaster) + " 의 옵션이 아닙니다: " + foreign.getId()));
        assertNothingMoved();
    }

    @Test
    void testNewOptionNameAlreadyTakenIs400() throws Exception {
        merge("""
                [
                  {"sourceOptionId": %d, "targetOptionId": %d},
                  {"sourceOptionId": %d, "newOptionName": "1개"}
                ]
                """.formatted(deleteOne.getId(), keepOne.getId(), deleteTwo.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("마스터 옵션명 중복: 1개"));
        assertNothingMoved();
    }

    @Test
    void testMasterEqualToTwoMastersBlocksMerge() throws Exception {
        // Legacy data (D27): a second master with the very set 「남길 단품」 has — saved past the service guard.
        MasterProduct legacy = master("남길 단품 옛것");
        component(legacy, target);

        merge(validChoices())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("구성품이 같아지는 마스터 상품이 둘 이상 있습니다: "
                        + label(deleteMaster) + ", " + label(keepMaster) + ", " + label(legacy)
                        + ". 마스터 구성 편집에서 먼저 정리해 주세요."));
        assertNothingMoved();
    }
}
