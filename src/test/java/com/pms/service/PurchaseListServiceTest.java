package com.pms.service;

import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.PurchaseRecord;
import com.pms.config.CoupangProperties;
import com.pms.domain.ShoppingListItem;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.ShoppingListItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PurchaseListServiceImpl 단위 테스트 — BOM 전개·멱등 추출·잔여 계산·미매핑·수동 누적의 핵심 로직만.
 * 프레임워크 검증(@NotNull 등)이나 단순 위임은 컨트롤러/통합 테스트로 미룬다.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseListServiceTest {

    @Mock private ShoppingListItemRepository shoppingListItemRepository;
    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private CoupangOrderLineRepository coupangOrderLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CoupangProperties coupangProperties;

    @InjectMocks private PurchaseListServiceImpl service;

    private Product product(Long id, String name) {
        return Product.builder().id(id).productName(name).build();
    }

    private static final MarketplaceAccount ACCOUNT =
            MarketplaceAccount.builder().id(1L).platform(Platform.COUPANG).build();

    private OrderLine paidLine(Long id, int orderQty) {
        return OrderLine.builder()
                .id(id)
                .order(Order.builder().id(1000L + id).marketplaceAccount(ACCOUNT).platform(Platform.COUPANG)
                        .externalOrderId("O" + id).build())
                .itemName("주문" + id).orderQty(orderQty).cancelQty(0).holdQty(0)
                .status(OrderStatus.PAID).build();
    }

    /** 옵션 매칭키(vendorItemId)는 core 가 아니라 쿠팡 거울에 있다(2609_26 / 04 §3-3). */
    private CoupangOrderLine mirror(OrderLine line, String optionId) {
        return CoupangOrderLine.builder()
                .id(2000L + line.getId()).orderLine(line).marketplaceAccount(ACCOUNT)
                .shipmentBoxId("B1").orderIdRaw(line.getOrder().getExternalOrderId())
                .vendorItemId(optionId).platformStatus("ACCEPT").build();
    }

    @Test
    void extract_BOM전개_옵션당구성수량만큼_autoQty계산() {
        OrderLine line = paidLine(10L, 3);                   // 발주가능 3
        Product a = product(100L, "A");
        Product b = product(200L, "B");
        ProductListingOption option = ProductListingOption.builder().id(1L).platformOptionId("OPT1").build();

        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of(line));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(List.of(10L))).willReturn(List.of(mirror(line, "OPT1")));
        given(productListingOptionRepository.findByPlatformOptionId("OPT1")).willReturn(Optional.of(option));
        given(productListingProductRepository.findByProductListingOptionId(1L)).willReturn(List.of(
                ProductListingProduct.builder().id(1L).product(a).quantity(2).build(),   // A×2 → 6
                ProductListingProduct.builder().id(2L).product(b).quantity(1).build()    // B×1 → 3
        ));
        given(shoppingListItemRepository.findByOrderLine_IdAndProduct_Id(anyLong(), anyLong()))
                .willReturn(Optional.empty());

        service.extract(null);

        verify(shoppingListItemRepository).resetAllAutoQty();
        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ShoppingListItem::getAutoQty)
                .containsExactly(6, 3);
    }

    @Test
    void extract_기존라인_manualQty보존_auto만갱신() {
        OrderLine line = paidLine(10L, 3);                   // 발주가능 3
        Product a = product(100L, "A");
        ProductListingOption option = ProductListingOption.builder().id(1L).platformOptionId("OPT1").build();
        ShoppingListItem existing = ShoppingListItem.builder()
                .id(5L).orderLine(line).product(a).autoQty(0).manualQty(4).build();

        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of(line));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(List.of(10L))).willReturn(List.of(mirror(line, "OPT1")));
        given(productListingOptionRepository.findByPlatformOptionId("OPT1")).willReturn(Optional.of(option));
        given(productListingProductRepository.findByProductListingOptionId(1L)).willReturn(List.of(
                ProductListingProduct.builder().id(1L).product(a).quantity(2).build()    // 3×2 = 6
        ));
        given(shoppingListItemRepository.findByOrderLine_IdAndProduct_Id(10L, 100L))
                .willReturn(Optional.of(existing));

        service.extract(null);

        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(captor.capture());
        assertThat(captor.getValue().getAutoQty()).isEqualTo(6);
        assertThat(captor.getValue().getManualQty()).isEqualTo(4);   // 보존
    }

    @Test
    void extract_옵션미매핑_save호출안함() {
        OrderLine line = paidLine(10L, 3);
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of(line));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(List.of(10L))).willReturn(List.of(mirror(line, "UNKNOWN")));
        given(productListingOptionRepository.findByPlatformOptionId("UNKNOWN")).willReturn(Optional.empty());

        service.extract(null);

        verify(shoppingListItemRepository).resetAllAutoQty();
        verify(shoppingListItemRepository, never()).save(any());
    }

    @Test
    void getList_잔여계산_잔여있으면그룹포함() {
        Product a = product(100L, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 8)).product(a).autoQty(8).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByItem_IdIn(List.of(1L))).willReturn(List.of(
                PurchaseRecord.builder().id(1L).item(sli).purchasedOn(LocalDate.now()).quantity(5).build()
        ));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseListResponse res = service.getList(null);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).neededQty()).isEqualTo(8);
        assertThat(res.items().get(0).purchasedQty()).isEqualTo(5);
        assertThat(res.items().get(0).remainingQty()).isEqualTo(3);
    }

    @Test
    void getList_잔여0이면그룹제외() {
        Product a = product(100L, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 5)).product(a).autoQty(5).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByItem_IdIn(List.of(1L))).willReturn(List.of(
                PurchaseRecord.builder().id(1L).item(sli).purchasedOn(LocalDate.now()).quantity(5).build()
        ));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseListResponse res = service.getList(null);

        assertThat(res.items()).isEmpty();
    }

    @Test
    void getList_결제완료인데옵션미매핑_unmappedOrders집계() {
        OrderLine line = paidLine(10L, 5);
        given(shoppingListItemRepository.findAll()).willReturn(List.of());
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of(line));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(List.of(10L))).willReturn(List.of(mirror(line, "X")));
        given(productListingOptionRepository.findByPlatformOptionId("X")).willReturn(Optional.empty());

        PurchaseListResponse res = service.getList(null);

        assertThat(res.items()).isEmpty();
        assertThat(res.unmappedOrders()).hasSize(1);
        assertThat(res.unmappedOrders().get(0).externalItemId()).isEqualTo("X");
        assertThat(res.unmappedOrders().get(0).purchasableQty()).isEqualTo(5);
        assertThat(res.unmappedOrders().get(0).orderCount()).isEqualTo(1);
    }

    @Test
    void addManual_기존수동라인존재_manualQty누적_새행안만듦() {
        Product a = product(100L, "A");
        ShoppingListItem existing = ShoppingListItem.builder()
                .id(7L).orderLine(null).product(a).autoQty(0).manualQty(4).build();
        given(shoppingListItemRepository.findByOrderLineIsNullAndProduct_Id(100L))
                .willReturn(Optional.of(existing));

        service.addManual(new ManualItemRequest(100L, 3));

        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(captor.capture());
        assertThat(captor.getValue().getManualQty()).isEqualTo(7);   // 4 + 3
        verify(productRepository, never()).findById(anyLong());      // 신규 product 조회 없음
    }

    // --- 매입 금액 (FEATURE_2609_28 / PLAN D1~D3) ---

    private ShoppingListItem purchaseItem() {
        return ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 8)).product(product(100L, "A"))
                .autoQty(8).manualQty(0).build();
    }

    /** addPurchase 를 태우고 저장된 PurchaseRecord 를 잡아 온다 — 계산 결과는 save() 인자로만 확인 가능하다. */
    private PurchaseRecord savedPurchase(PurchaseRecordRequest request) {
        given(shoppingListItemRepository.findById(1L)).willReturn(Optional.of(purchaseItem()));

        service.addPurchase(1L, request);

        ArgumentCaptor<PurchaseRecord> captor = ArgumentCaptor.forClass(PurchaseRecord.class);
        verify(purchaseRecordRepository).save(captor.capture());
        return captor.getValue();
    }

    private PurchaseRecordRequest req(int quantity, String total, String unit, Boolean reflect) {
        return new PurchaseRecordRequest(LocalDate.now(), quantity,
                total == null ? null : new BigDecimal(total),
                unit == null ? null : new BigDecimal(unit),
                reflect);
    }

    @Test
    void testAddPurchaseWithTotalDerivesUnitPrice() {
        PurchaseRecord saved = savedPurchase(req(3, "12000", null, null));

        assertThat(saved.getTotalAmount()).isEqualByComparingTo("12000.00");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("4000.0000");
    }

    @Test
    void testAddPurchaseWithUnitDerivesTotal() {
        PurchaseRecord saved = savedPurchase(req(3, null, "4000", null));

        assertThat(saved.getTotalAmount()).isEqualByComparingTo("12000.00");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("4000.0000");
    }

    /** 요점: 나눠떨어지지 않아도 실지불액(총액)은 흔들리지 않는다(D1). */
    @Test
    void testAddPurchaseWithIndivisibleTotalKeepsTotalExact() {
        PurchaseRecord saved = savedPurchase(req(3, "10000", null, null));

        assertThat(saved.getTotalAmount()).isEqualByComparingTo("10000.00");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("3333.3333");
    }

    @Test
    void testAddPurchaseWithBothAmountsRejected() {
        given(shoppingListItemRepository.findById(1L)).willReturn(Optional.of(purchaseItem()));

        assertThatThrownBy(() -> service.addPurchase(1L, req(3, "12000", "4000", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(purchaseRecordRepository, never()).save(any());
    }

    @Test
    void testAddPurchaseWithZeroQuantityRejected() {
        given(shoppingListItemRepository.findById(1L)).willReturn(Optional.of(purchaseItem()));

        assertThatThrownBy(() -> service.addPurchase(1L, req(0, "12000", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(purchaseRecordRepository, never()).save(any());
    }

    /** 정정 행: 수량·금액 모두 음수 → 단가는 양수로 남는다. */
    @Test
    void testAddPurchaseWithNegativeQuantityKeepsPositiveUnitPrice() {
        PurchaseRecord saved = savedPurchase(req(-3, "-12000", null, null));

        assertThat(saved.getQuantity()).isEqualTo(-3);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("-12000.00");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("4000.0000");
    }

    @Test
    void testAddPurchaseDefaultsReflectToBasePriceTrue() {
        assertThat(savedPurchase(req(3, "12000", null, null)).getReflectToBasePrice()).isTrue();
        assertThat(PurchaseRecord.of(purchaseItem(), req(3, "12000", null, false)).getReflectToBasePrice()).isFalse();
    }

    /** 금액 미상 행: null 을 0 으로 치환하지 않는다. */
    @Test
    void testAddPurchaseWithoutAmountSaves() {
        PurchaseRecord saved = savedPurchase(req(5, null, null, null));

        assertThat(saved.getQuantity()).isEqualTo(5);
        assertThat(saved.getTotalAmount()).isNull();
        assertThat(saved.getUnitPrice()).isNull();
    }
}
