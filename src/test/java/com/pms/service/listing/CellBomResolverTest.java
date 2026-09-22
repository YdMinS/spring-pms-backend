package com.pms.service.listing;

import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.repository.MasterProductOptionItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 셀 옵션 → 마스터 BOM 해석(FEATURE_2609_71 / 01). 리포지토리는 mock — 이 클래스는 순수 조합이다.
 *
 * <p>핵심은 둘이다: 채널 전용 옵션이 <b>빈 목록이 아니라 미매핑</b>으로 나오는가, 그리고 옵션이 여러 개여도
 * 조회가 <b>한 번</b>인가(반복문 안의 단건 조회는 N+1).</p>
 */
@ExtendWith(MockitoExtension.class)
class CellBomResolverTest {

    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @InjectMocks private CellBomResolver resolver;

    private MasterProductOption masterOption(Long id) {
        return MasterProductOption.builder().id(id).name("옵션" + id).build();
    }

    private ProductListingOption cellOption(Long id, MasterProductOption masterOption) {
        return ProductListingOption.builder().id(id).optionName("셀옵션" + id)
                .masterProductOption(masterOption).build();
    }

    private Product product(Long id, String name) {
        return Product.builder().id(id).productName(name).build();
    }

    private MasterProductOptionItem item(MasterProductOption option, Product product, int quantity) {
        return MasterProductOptionItem.builder().id(product.getId()).option(option).product(product)
                .quantity(quantity).build();
    }

    @Test
    void testForOptionReturnsMasterBom() {
        MasterProductOption master = masterOption(663L);
        Product wash = product(1231L, "발수 코팅 워셔액 (보라)");
        given(masterProductOptionItemRepository.findWithProductByOptionIdIn(List.of(663L)))
                .willReturn(List.of(item(master, wash, 2)));

        CellBomResolver.Bom bom = resolver.forOption(cellOption(709L, master));

        assertThat(bom.unmapped()).isFalse();
        assertThat(bom.lines()).containsExactly(new CellBomResolver.Line(1231L, wash, 2));
        assertThat(bom.lines().get(0).productName()).isEqualTo("발수 코팅 워셔액 (보라)");
    }

    /** 🔴 채널 전용 = 구성품을 알 수 없다. 빈 목록으로 뭉개면 원가·발주량이 조용히 0 이 된다. */
    @Test
    void testForOptionChannelOnlyIsUnmapped() {
        CellBomResolver.Bom bom = resolver.forOption(cellOption(709L, null));

        assertThat(bom.unmapped()).isTrue();
        assertThat(bom.lines()).isEmpty();
        assertThat(bom.isEmpty()).isTrue();
        assertThat(bom.first()).isNull();
    }

    @Test
    void testForOptionsBatchesOneQuery() {
        MasterProductOption m1 = masterOption(1L);
        MasterProductOption m2 = masterOption(2L);
        MasterProductOption m3 = masterOption(3L);
        Product water = product(10L, "생수");
        given(masterProductOptionItemRepository.findWithProductByOptionIdIn(anyCollection()))
                .willReturn(List.of(item(m1, water, 1), item(m2, water, 2), item(m3, water, 3)));

        Map<Long, CellBomResolver.Bom> boms = resolver.forOptions(List.of(
                cellOption(101L, m1), cellOption(102L, m2), cellOption(103L, m3)));

        assertThat(boms).hasSize(3);
        assertThat(boms.get(101L).lines()).extracting(CellBomResolver.Line::quantity).containsExactly(1);
        assertThat(boms.get(103L).lines()).extracting(CellBomResolver.Line::quantity).containsExactly(3);
        verify(masterProductOptionItemRepository, times(1)).findWithProductByOptionIdIn(anyCollection());
    }

    /** 마스터 옵션은 있는데 구성품이 0개 = 미매핑이 아니다(마스터는 있다). */
    @Test
    void testForOptionsKeepsOptionsWithoutItems() {
        MasterProductOption master = masterOption(5L);
        given(masterProductOptionItemRepository.findWithProductByOptionIdIn(anyCollection()))
                .willReturn(List.of());

        Map<Long, CellBomResolver.Bom> boms = resolver.forOptions(List.of(cellOption(50L, master)));

        assertThat(boms.get(50L).unmapped()).isFalse();
        assertThat(boms.get(50L).lines()).isEmpty();
    }

    /** 채널 전용 옵션이 섞여도 결과 Map 에는 모든 옵션이 들어 있다 — 호출자가 건너뛴 사실을 알아야 한다. */
    @Test
    void testForOptionsIncludesChannelOnlyAsUnmapped() {
        MasterProductOption master = masterOption(7L);
        Product water = product(10L, "생수");
        given(masterProductOptionItemRepository.findWithProductByOptionIdIn(List.of(7L)))
                .willReturn(List.of(item(master, water, 4)));

        Map<Long, CellBomResolver.Bom> boms = resolver.forOptions(List.of(
                cellOption(70L, master), cellOption(71L, null)));

        assertThat(boms.get(70L).unmapped()).isFalse();
        assertThat(boms.get(71L).unmapped()).isTrue();
        assertThat(boms.get(71L).lines()).isEmpty();
    }
}
