package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Pure value-assembly generator (option count → component count branch, brand omission). Repositories are
 * mocked; no string is hard-coded (all values come from the entities).
 */
@ExtendWith(MockitoExtension.class)
class RegistrationNameGeneratorTest {

    @Mock private MasterProductOptionRepository optionRepository;
    @Mock private MasterProductOptionItemRepository optionItemRepository;
    @Mock private MasterProductComponentRepository componentRepository;
    @Mock private ProductRepository productRepository;
    @InjectMocks private RegistrationNameGenerator generator;

    private MasterProduct master() {
        return MasterProduct.builder().id(1L).name("마스터A").build();
    }

    private Product product(Long id, String brand, String name) {
        return Product.builder().id(id).brand(brand).productName(name).build();
    }

    private MasterProductOptionItem item(Product product, int quantity) {
        return MasterProductOptionItem.builder().product(product).quantity(quantity).build();
    }

    @Test
    void 옵션2개이상_구성상품콤마나열_옵션확인() {
        Product water = product(10L, "노브랜드", "생수");
        Product softener = product(20L, "다우니", "섬유유연제");
        given(optionRepository.findByMasterProductId(1L)).willReturn(List.of(
                MasterProductOption.builder().id(5L).build(),
                MasterProductOption.builder().id(6L).build()));
        given(componentRepository.findByMasterProductId(1L)).willReturn(List.of(
                MasterProductComponent.builder().product(water).build(),
                MasterProductComponent.builder().product(softener).build()));
        given(productRepository.findAllById(List.of(10L, 20L))).willReturn(List.of(water, softener));

        assertThat(generator.generate(master())).isEqualTo("노브랜드 생수, 다우니 섬유유연제 - 옵션확인");
    }

    @Test
    void 옵션1개_단일구성_수량표기() {
        given(optionRepository.findByMasterProductId(1L))
                .willReturn(List.of(MasterProductOption.builder().id(5L).build()));
        given(optionItemRepository.findByOptionId(5L))
                .willReturn(List.of(item(product(10L, "노브랜드", "생수"), 6)));

        assertThat(generator.generate(master())).isEqualTo("노브랜드 생수 x 6");
    }

    @Test
    void 옵션1개_멀티구성_플러스연결() {
        given(optionRepository.findByMasterProductId(1L))
                .willReturn(List.of(MasterProductOption.builder().id(5L).build()));
        given(optionItemRepository.findByOptionId(5L)).willReturn(List.of(
                item(product(10L, "노브랜드", "생수"), 2),
                item(product(20L, "다우니", "섬유유연제"), 1)));

        assertThat(generator.generate(master())).isEqualTo("노브랜드 생수 x 2 + 다우니 섬유유연제 x 1");
    }

    @Test
    void 브랜드null_생략() {
        given(optionRepository.findByMasterProductId(1L))
                .willReturn(List.of(MasterProductOption.builder().id(5L).build()));
        given(optionItemRepository.findByOptionId(5L))
                .willReturn(List.of(item(product(10L, null, "생수"), 6)));

        assertThat(generator.generate(master())).isEqualTo("생수 x 6");
    }

    // ---------------------------------------------------------------- per-channel overload (67)

    @Test
    void 채널_활성옵션1개_단일수량표기() {
        // 67: name is generated from the LISTING's active options (injected), not the master's full option count.
        MasterProductOption only = MasterProductOption.builder().id(5L).name("1세트").build();
        given(optionItemRepository.findByOptionId(5L))
                .willReturn(List.of(item(product(10L, "노브랜드", "생수"), 6)));

        String name = generator.generate(master(), List.of("1세트"), List.of(only));

        assertThat(name).isEqualTo("노브랜드 생수 x 6");
    }

    @Test
    void 채널_활성옵션2개_구성상품콤마나열_옵션확인() {
        Product water = product(10L, "노브랜드", "생수");
        Product softener = product(20L, "다우니", "섬유유연제");
        given(componentRepository.findByMasterProductId(1L)).willReturn(List.of(
                MasterProductComponent.builder().product(water).build(),
                MasterProductComponent.builder().product(softener).build()));
        given(productRepository.findAllById(List.of(10L, 20L))).willReturn(List.of(water, softener));

        String name = generator.generate(master(), List.of("1세트", "2세트"), List.of(
                MasterProductOption.builder().id(5L).name("1세트").build(),
                MasterProductOption.builder().id(6L).name("2세트").build()));

        assertThat(name).isEqualTo("노브랜드 생수, 다우니 섬유유연제 - 옵션확인");
    }

    @Test
    void 채널_활성옵션0개_또는_이름미매칭_마스터이름폴백() {
        MasterProductOption only = MasterProductOption.builder().id(5L).name("1세트").build();

        // 0 active options → master name fallback.
        assertThat(generator.generate(master(), List.of(), List.of(only))).isEqualTo("마스터A");
        // 1 active option whose name matches nothing in masterOptions → master name fallback (defensive).
        assertThat(generator.generate(master(), List.of("없는옵션"), List.of(only))).isEqualTo("마스터A");
    }
}
