package com.pms.service.stock;

import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OrderLine;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * OrderLineExpander — 마스터 BOM 전개(D13)와 중립 링크/거울 행 폴백(D15).
 *
 * <p>여기서 지키는 계약은 둘이다: <b>전개는 마스터 BOM 만 탄다</b>(채널 셀 BOM 리포지토리는 주입조차
 * 되지 않는다), 그리고 <b>실패는 목록에 실려 나온다</b>(조용히 빠지지 않는다).
 */
@ExtendWith(MockitoExtension.class)
class OrderLineExpanderTest {

    private static final Long LINE_ID = 11L;
    private static final Long MASTER_OPTION_ID = 22L;
    private static final Long PRODUCT_ID = 33L;
    private static final String VENDOR_ITEM_ID = "vi-1";

    @Mock private CoupangOrderLineRepository coupangOrderLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;

    @InjectMocks private OrderLineExpander expander;

    private static Product product() {
        return Product.builder().id(PRODUCT_ID).productName("양말A").build();
    }

    private static MasterProductOption masterOption() {
        return MasterProductOption.builder().id(MASTER_OPTION_ID).build();
    }

    private static ProductListingOption listingOption(MasterProductOption master) {
        return ProductListingOption.builder().id(99L).platformOptionId(VENDOR_ITEM_ID)
                .masterProductOption(master).build();
    }

    private static OrderLine line(ProductListingOption option, int orderQty) {
        return OrderLine.builder().id(LINE_ID).orderQty(orderQty).cancelQty(0).holdQty(0)
                .itemName("양말A 3켤레").productListingOption(option).build();
    }

    private static MasterProductOptionItem bomItem(int quantity) {
        return MasterProductOptionItem.builder()
                .option(masterOption()).product(product()).quantity(quantity).build();
    }

    private Map<Long, OrderLineExpander.LineExpansion> expand(OrderLine line) {
        return expander.expand(List.of(line), OrderLine::getOrderQty);
    }

    @Test
    void testExpandsThroughMasterOption() {
        given(masterProductOptionItemRepository.findWithProductByOptionIdIn(anyCollection()))
                .willReturn(List.of(bomItem(2)));

        OrderLineExpander.LineExpansion result = expand(line(listingOption(masterOption()), 3)).get(LINE_ID);

        assertThat(result.failed()).isFalse();
        assertThat(result.products()).singleElement().satisfies(p -> {
            assertThat(p.productId()).isEqualTo(PRODUCT_ID);
            assertThat(p.productName()).isEqualTo("양말A");
            assertThat(p.quantity()).isEqualTo(6);   // BOM 2 × 주문 3
        });
    }

    /** 🔴 미매핑을 조용히 continue 하지 않는다 — 목록 누락으로 드러나야 사람이 고친다(D13). */
    @Test
    void testUnmappedOptionReportedNotSkipped() {
        given(coupangOrderLineRepository.findByOrderLine_IdIn(List.of(LINE_ID))).willReturn(List.of());

        OrderLineExpander.LineExpansion result = expand(line(null, 3)).get(LINE_ID);

        assertThat(result.failed()).isTrue();
        assertThat(result.failure()).isEqualTo(OrderLineExpander.Failure.UNMAPPED_OPTION);
        assertThat(result.products()).isEmpty();
    }

    @Test
    void testNullMasterOptionReported() {
        OrderLineExpander.LineExpansion result = expand(line(listingOption(null), 3)).get(LINE_ID);

        assertThat(result.failure()).isEqualTo(OrderLineExpander.Failure.NO_MASTER_OPTION);
    }

    @Test
    void testEmptyBomReported() {
        given(masterProductOptionItemRepository.findWithProductByOptionIdIn(anyCollection()))
                .willReturn(List.of());

        OrderLineExpander.LineExpansion result = expand(line(listingOption(masterOption()), 3)).get(LINE_ID);

        assertThat(result.failure()).isEqualTo(OrderLineExpander.Failure.EMPTY_BOM);
    }

    /** 백필이 채우지 못한 과거 라인 — 거울 행으로 1회 폴백한다(D15). 이 경로가 유일한 CoupangOrderLine 사용처다. */
    @Test
    void testFallsBackToCoupangMirrorWhenFkNull() {
        OrderLine orderLine = line(null, 3);
        given(coupangOrderLineRepository.findByOrderLine_IdIn(List.of(LINE_ID)))
                .willReturn(List.of(CoupangOrderLine.builder()
                        .orderLine(orderLine).vendorItemId(VENDOR_ITEM_ID).build()));
        given(productListingOptionRepository.findByPlatformOptionId(VENDOR_ITEM_ID))
                .willReturn(Optional.of(listingOption(masterOption())));
        given(masterProductOptionItemRepository.findWithProductByOptionIdIn(anyCollection()))
                .willReturn(List.of(bomItem(2)));

        OrderLineExpander.LineExpansion result = expand(orderLine).get(LINE_ID);

        assertThat(result.failed()).isFalse();
        assertThat(result.products()).singleElement()
                .satisfies(p -> assertThat(p.quantity()).isEqualTo(6));
    }

    /**
     * 🔴 채널 셀 BOM 은 전개에 쓰지 않는다(D13). 셀 BOM 이 5 를 말해도 결과는 마스터 값(2×3=6)이다 —
     * 이 클래스에는 {@code ProductListingProductRepository} 가 아예 주입되지 않으므로 셀 값을 볼 방법이 없다.
     */
    @Test
    void testDoesNotUseChannelCellBom() {
        given(masterProductOptionItemRepository.findWithProductByOptionIdIn(anyCollection()))
                .willReturn(List.of(bomItem(2)));   // 셀 BOM 은 같은 물품에 5 를 들고 있다고 가정

        OrderLineExpander.LineExpansion result = expand(line(listingOption(masterOption()), 3)).get(LINE_ID);

        assertThat(result.products()).singleElement()
                .satisfies(p -> assertThat(p.quantity()).isEqualTo(6));
        assertThat(OrderLineExpander.class.getDeclaredFields())
                .noneMatch(f -> f.getType().getSimpleName().contains("ProductListingProduct"));
    }
}
