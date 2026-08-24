package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.ProductListing;
import com.pms.repository.CategoryMappingRepository;
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
    @InjectMocks private MasterChannelConfigServiceImpl service;

    private CarrierRate carrier(String cost) {
        return CarrierRate.builder().cost(new BigDecimal(cost)).build();
    }

    private Package box(String cost) {
        return Package.builder().cost(new BigDecimal(cost)).build();
    }

    private ProductListing cell(MasterProduct master) {
        return ProductListing.builder().id(1L).platform("COUPANG").masterProduct(master).build();
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
    void resolvePlatformCategoryCode_mappingPresent_returnsCode() {
        Category category = Category.builder().id(5L).name("신발").build();
        MasterProduct master = MasterProduct.builder().id(9L).category(category).build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(5L, "COUPANG"))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .category(category).platform("COUPANG").platformCategoryId("101").build()));

        assertThat(service.resolvePlatformCategoryCode(cell(master))).isEqualTo("101");
    }

    @Test
    void resolvePlatformCategoryCode_noMapping_throws400() {
        Category category = Category.builder().id(5L).name("신발").build();
        MasterProduct master = MasterProduct.builder().id(9L).category(category).build();
        given(categoryMappingRepository.findByCategoryIdAndPlatform(5L, "COUPANG"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolvePlatformCategoryCode(cell(master)))
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
