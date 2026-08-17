package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductCategory;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.ProductListing;
import com.pms.repository.MasterProductCategoryRepository;
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
 * Channel-config resolver (FEATURE_2608_06 / 13): category = master × platform (400 if unset), and
 * delivery/box = option override ?? master default (400 if both null). The resolver is the single owner of
 * these null checks.
 */
@ExtendWith(MockitoExtension.class)
class MasterChannelConfigServiceTest {

    @Mock private MasterProductCategoryRepository categoryRepository;
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

    // ---- category ----

    @Test
    void resolveCategory_present_returnsMasterPlatformCategory() {
        MasterProduct master = MasterProduct.builder().id(9L).build();
        Category category = Category.builder().id(3L).name("신발").build();
        given(categoryRepository.findByMasterProductIdAndPlatform(9L, "COUPANG"))
                .willReturn(Optional.of(MasterProductCategory.builder()
                        .masterProduct(master).platform("COUPANG").category(category).build()));

        assertThat(service.resolveCategory(cell(master)).getId()).isEqualTo(3L);
    }

    @Test
    void resolveCategory_missing_throws400() {
        MasterProduct master = MasterProduct.builder().id(9L).build();
        given(categoryRepository.findByMasterProductIdAndPlatform(9L, "COUPANG"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCategory(cell(master)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("카테고리 미설정");
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
