package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Channel-config resolver (FEATURE_2608_06 / 44): standard category = master.category (400 if unset), the
 * platform code = the standard category's CategoryMapping for the cell platform (400 if no mapping), and
 * delivery/box = option override ?? master default (400 if both null). The resolver is the single owner of
 * these null checks.
 */
@ExtendWith(MockitoExtension.class)
class MasterChannelConfigServiceTest {

    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @InjectMocks private MasterChannelConfigServiceImpl service;

    private CarrierRate carrier(String cost) {
        return CarrierRate.builder().cost(new BigDecimal(cost)).build();
    }

    private Package box(String cost) {
        return Package.builder().cost(new BigDecimal(cost)).build();
    }

    private ProductListing cell(MasterProduct master) {
        return ProductListing.builder().id(1L).platform(Platform.COUPANG).masterProduct(master).build();
    }

    // ---- 2609_45/D9·D10·D10-1·D11: channel-owned category ----

    private ProductListing cell(MasterProduct master, String platformCategoryCode) {
        return ProductListing.builder().id(1L).platform(Platform.COUPANG).masterProduct(master)
                .platformCategoryCode(platformCategoryCode).build();
    }

    private PlatformCategory platformCategory(Long id, String code, String commissionRate) {
        return PlatformCategory.builder().id(id).platform(Platform.COUPANG).code(code).name("cat-" + code)
                .commissionRate(commissionRate == null ? null : new BigDecimal(commissionRate)).build();
    }

    /** Master → standard category → CategoryMapping → PlatformCategory("58630"). */
    private MasterProduct masterMappedTo58630() {
        Category category = Category.builder().id(5L).name("즉석밥").build();
        MasterProduct master = MasterProduct.builder().id(9L).category(category).build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(5L, Platform.COUPANG))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .category(category).platform(Platform.COUPANG)
                        .platformCategory(platformCategory(20L, "58630", "0.11")).build()));
        return master;
    }

    @Test
    void resolveChannelCategory_cellCodeDiffersAndHasCommission_usesChannelCategory() {
        MasterProduct master = masterMappedTo58630();
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "73170"))
                .willReturn(Optional.of(platformCategory(30L, "73170", "0.11")));

        var resolved = service.resolveChannelCategory(cell(master, "73170"));

        assertThat(resolved.category().getCode()).isEqualTo("73170");
        assertThat(resolved.own()).isTrue();
    }

    @Test
    void resolveChannelCategory_cellCodeWithoutCommission_fallsBackToMaster() {
        // D11: no commission = the selling-price reverse-calc would 400 → keep the master category quietly.
        MasterProduct master = masterMappedTo58630();
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "73170"))
                .willReturn(Optional.of(platformCategory(30L, "73170", null)));

        var resolved = service.resolveChannelCategory(cell(master, "73170"));

        assertThat(resolved.category().getCode()).isEqualTo("58630");
        assertThat(resolved.own()).isFalse();
    }

    @Test
    void resolveChannelCategory_cellCodeNotSeeded_fallsBackToMaster() {
        MasterProduct master = masterMappedTo58630();
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "73170"))
                .willReturn(Optional.empty());

        var resolved = service.resolveChannelCategory(cell(master, "73170"));

        assertThat(resolved.category().getCode()).isEqualTo("58630");
        assertThat(resolved.own()).isFalse();
    }

    @Test
    void resolveChannelCategory_noCellCode_usesMaster() {
        MasterProduct master = masterMappedTo58630();

        var resolved = service.resolveChannelCategory(cell(master));

        assertThat(resolved.category().getCode()).isEqualTo("58630");
        assertThat(resolved.own()).isFalse();
    }

    @Test
    void resolveChannelCategory_masterUnresolvable_survivesOnTheCellCategory() {
        // Nothing to compare against → the cell's own category is by definition its own.
        MasterProduct master = MasterProduct.builder().id(9L).category(null).build();
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "73170"))
                .willReturn(Optional.of(platformCategory(30L, "73170", "0.11")));

        var resolved = service.resolveChannelCategory(cell(master, "73170"));

        assertThat(resolved.category().getCode()).isEqualTo("73170");
        assertThat(resolved.own()).isTrue();
    }

    @Test
    void resolveChannelCategory_neitherMasterNorCellCategory_throws400() {
        MasterProduct master = MasterProduct.builder().id(9L).category(null).build();

        assertThatThrownBy(() -> service.resolveChannelCategory(cell(master)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("표준 카테고리 미설정");
    }

    @Test
    void resolveChannelCategory_cellCodeEqualsMasterCode_isNotOwn() {
        // 🔴 D10-1: the import stores platform_category_code even when it matches, so every pre-existing
        //    imported cell lands here. Judging by code-presence would strip the master attributes from the
        //    merge base for all of them.
        MasterProduct master = masterMappedTo58630();
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "58630"))
                .willReturn(Optional.of(platformCategory(20L, "58630", "0.11")));

        var resolved = service.resolveChannelCategory(cell(master, "58630"));

        assertThat(resolved.category().getCode()).isEqualTo("58630");
        assertThat(resolved.own()).isFalse();
    }

    @Test
    void resolvePlatformCategory_alwaysMatchesResolveChannelCategory() {
        MasterProduct master = masterMappedTo58630();
        given(platformCategoryRepository.findByPlatformAndCode(Platform.COUPANG, "73170"))
                .willReturn(Optional.of(platformCategory(30L, "73170", "0.11")));
        ProductListing cell = cell(master, "73170");

        assertThat(service.resolvePlatformCategory(cell))
                .isSameAs(service.resolveChannelCategory(cell).category());
        assertThat(service.resolvePlatformCategoryCode(cell)).isEqualTo("73170");
    }

    @Test
    void resolveStandardCategory_ignoresTheChannelCategoryCode() {
        // The commission-id path is untouched by this feature — it stays the master's standard category.
        Category category = Category.builder().id(5L).name("즉석밥").build();
        MasterProduct master = MasterProduct.builder().id(9L).category(category).build();

        assertThat(service.resolveStandardCategory(cell(master, "73170")).getId()).isEqualTo(5L);
    }

    // ---- standard category ----

    @Test
    void resolveStandardCategory_present_returnsMasterCategory() {
        Category category = Category.builder().id(3L).name("신발").build();
        MasterProduct master = MasterProduct.builder().id(9L).category(category).build();

        assertThat(service.resolveStandardCategory(cell(master)).getId()).isEqualTo(3L);
    }

    @Test
    void resolveStandardCategory_missing_throws400() {
        MasterProduct master = MasterProduct.builder().id(9L).category(null).build();

        assertThatThrownBy(() -> service.resolveStandardCategory(cell(master)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("표준 카테고리 미설정");
    }

    // ---- platform category code (standard category × platform mapping) ----

    @Test
    void resolvePlatformCategoryCode_mappingLinked_returnsPlatformCategoryCode() {
        // 52: the code comes from the mapping's linked PlatformCategory FK, not the (deprecated) string column.
        Category category = Category.builder().id(5L).name("신발").build();
        MasterProduct master = MasterProduct.builder().id(9L).category(category).build();
        PlatformCategory platformCategory = PlatformCategory.builder()
                .id(20L).platform(Platform.COUPANG).code("101").name("운동화").build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(5L, Platform.COUPANG))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .category(category).platform(Platform.COUPANG).platformCategoryId("legacy")
                        .platformCategory(platformCategory).build()));

        assertThat(service.resolvePlatformCategory(cell(master)).getCommissionRate()).isNull();
        assertThat(service.resolvePlatformCategoryCode(cell(master))).isEqualTo("101");
    }

    @Test
    void resolvePlatformCategoryCode_noMapping_throws400() {
        Category category = Category.builder().id(5L).name("신발").build();
        MasterProduct master = MasterProduct.builder().id(9L).category(category).build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(5L, Platform.COUPANG))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolvePlatformCategoryCode(cell(master)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("COUPANG 카테고리 매핑 미설정");
    }

    // ---- platform category code by category id (57 — schema lookup before a master exists) ----

    @Test
    void resolvePlatformCategoryCodeByCategoryId_mappingLinked_returnsCode() {
        Category category = Category.builder().id(7L).name("신발").build();
        PlatformCategory platformCategory = PlatformCategory.builder()
                .id(20L).platform(Platform.COUPANG).code("202").name("운동화").build();
        given(categoryRepository.findById(7L)).willReturn(Optional.of(category));
        given(categoryMappingRepository.findByCategoryIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .category(category).platform(Platform.COUPANG).platformCategory(platformCategory).build()));

        assertThat(service.resolvePlatformCategoryCode(7L, Platform.COUPANG)).isEqualTo("202");
    }

    @Test
    void resolvePlatformCategoryCodeByCategoryId_noMapping_throws400() {
        Category category = Category.builder().id(7L).name("신발").build();
        given(categoryRepository.findById(7L)).willReturn(Optional.of(category));
        given(categoryMappingRepository.findByCategoryIdAndPlatform(7L, Platform.COUPANG))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolvePlatformCategoryCode(7L, Platform.COUPANG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("COUPANG 카테고리 매핑 미설정");
    }

    @Test
    void resolvePlatformCategoryCodeByCategoryId_categoryNotFound_throws400() {
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolvePlatformCategoryCode(99L, Platform.COUPANG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("카테고리 없음");
    }

    @Test
    void resolvePlatformCategory_mappingNotLinked_throws400() {
        // Mapping present but its PlatformCategory FK is still null (transition) = not seeded yet → 400.
        Category category = Category.builder().id(5L).name("신발").build();
        MasterProduct master = MasterProduct.builder().id(9L).category(category).build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(5L, Platform.COUPANG))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .category(category).platform(Platform.COUPANG).platformCategoryId("legacy").build()));

        assertThatThrownBy(() -> service.resolvePlatformCategory(cell(master)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("COUPANG 카테고리 매핑 미설정");
    }

    // ---- delivery: option override ?? master default ----

    @Test
    void resolveDelivery_optionOverride_wins() {
        MasterProduct master = MasterProduct.builder().id(9L).defaultDelivery(carrier("500")).build();
        MasterProductOption option = MasterProductOption.builder().delivery(carrier("700")).build();

        assertThat(service.resolveDelivery(cell(master), option).getCost()).isEqualByComparingTo("700");
    }

    @Test
    void resolveDelivery_optionNull_fallsBackToMasterDefault() {
        MasterProduct master = MasterProduct.builder().id(9L).defaultDelivery(carrier("500")).build();
        MasterProductOption option = MasterProductOption.builder().delivery(null).build();

        assertThat(service.resolveDelivery(cell(master), option).getCost()).isEqualByComparingTo("500");
    }

    @Test
    void resolveDelivery_bothNull_throws400() {
        MasterProduct master = MasterProduct.builder().id(9L).defaultDelivery(null).build();

        assertThatThrownBy(() -> service.resolveDelivery(cell(master), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송 미설정");
    }

    // ---- box: option override ?? master default ----

    @Test
    void resolvePackage_optionOverride_wins() {
        MasterProduct master = MasterProduct.builder().id(9L).defaultPackage(box("300")).build();
        MasterProductOption option = MasterProductOption.builder().package_(box("450")).build();

        assertThat(service.resolvePackage(cell(master), option).getCost()).isEqualByComparingTo("450");
    }

    @Test
    void resolvePackage_bothNull_throws400() {
        MasterProduct master = MasterProduct.builder().id(9L).defaultPackage(null).build();

        assertThatThrownBy(() -> service.resolvePackage(cell(master), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("박스 미설정");
    }
}
