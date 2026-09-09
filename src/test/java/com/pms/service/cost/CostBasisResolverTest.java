package com.pms.service.cost;

import com.pms.domain.CostBasis;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.service.stock.OrderLineExpander;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CostBasisResolver — 등급 판정과 금액 합산, 그리고 "한 번만 굽는다".
 *
 * <p>🔴 {@code PURCHASED} 등급 테스트가 없는 것은 누락이 아니다 — 2609_29 가 라인↔매입 링크를 드롭해
 * 그 등급 자체가 사라졌다({@link CostBasis} javadoc).
 */
@ExtendWith(MockitoExtension.class)
class CostBasisResolverTest {

    private static final Long LINE_ID = 11L;
    private static final Long PRODUCT_ID = 33L;
    private static final Long OTHER_PRODUCT_ID = 34L;
    private static final Long SELLER_ID = 5L;
    private static final LocalDate ORDERED_ON = LocalDate.of(2026, 9, 1);

    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private ProductRepository productRepository;
    @Mock private OrderLineRepository orderLineRepository;

    @InjectMocks private CostBasisResolver resolver;

    // --- fixtures ---

    private static OrderLine line(CostBasis costBasis) {
        Seller seller = Seller.builder().id(SELLER_ID).sellerName("셀러A").build();
        MarketplaceAccount account = MarketplaceAccount.builder().id(1L).seller(seller).build();
        Order order = Order.builder().id(7L).marketplaceAccount(account)
                .externalOrderId("ORD-1").orderedAt(ORDERED_ON.atTime(10, 0)).build();
        return OrderLine.builder().id(LINE_ID).order(order).itemName("양말A 3켤레")
                .orderQty(3).cancelQty(0).holdQty(0).costBasis(costBasis).build();
    }

    private static OrderLine line() {
        return line(null);
    }

    private static OrderLineExpander.ExpandedProduct expanded(Long productId, int quantity) {
        return new OrderLineExpander.ExpandedProduct(productId, "양말A", quantity);
    }

    private static PurchaseRecord purchase(String unitPrice, LocalDate purchasedOn) {
        return PurchaseRecord.builder().id(1L).purchasedOn(purchasedOn).quantity(3)
                .unitPrice(new BigDecimal(unitPrice)).reflectToBasePrice(true).build();
    }

    private void givenPurchase(Long productId, PurchaseRecord... records) {
        given(purchaseRecordRepository.findLatestPriced(eq(productId), eq(SELLER_ID), any(), any()))
                .willReturn(List.of(records));
    }

    private void givenProducts(Product... products) {
        lenient().when(productRepository.findAllById(anyList())).thenReturn(List.of(products));
    }

    private static Product product(Long id, String price) {
        return Product.builder().id(id).productName("양말A")
                .price(price == null ? null : new BigDecimal(price)).build();
    }

    // --- 등급 판정 ---

    @Test
    void testSellerPurchaseHistoryGivesLatest() {
        givenPurchase(PRODUCT_ID, purchase("4000", ORDERED_ON.minusDays(3)));
        givenProducts(product(PRODUCT_ID, "9999"));

        CostBasisResolver.LineCost cost = resolver.resolve(line(), List.of(expanded(PRODUCT_ID, 3)));

        assertThat(cost.basis()).isEqualTo(CostBasis.LATEST);
        // 기준가(9999)가 아니라 실제 매입 단가를 쓴다.
        assertThat(cost.amount()).isEqualByComparingTo("12000");
    }

    @Test
    void testLatestIgnoresPurchasesAfterOrderDate() {
        givenPurchase(PRODUCT_ID, purchase("4000", ORDERED_ON.minusDays(3)));
        givenProducts(product(PRODUCT_ID, "9999"));

        resolver.resolve(line(), List.of(expanded(PRODUCT_ID, 1)));

        ArgumentCaptor<LocalDate> asOf = ArgumentCaptor.forClass(LocalDate.class);
        verify(purchaseRecordRepository)
                .findLatestPriced(eq(PRODUCT_ID), eq(SELLER_ID), asOf.capture(), any());
        // 주문 이후에 들어온 매입은 이 주문의 원가 근거가 아니다 — 컷오프는 주문일이다.
        assertThat(asOf.getValue()).isEqualTo(ORDERED_ON);
    }

    @Test
    void testNoPurchaseHistoryFallsToListed() {
        givenPurchase(PRODUCT_ID);
        givenProducts(product(PRODUCT_ID, "3000"));

        CostBasisResolver.LineCost cost = resolver.resolve(line(), List.of(expanded(PRODUCT_ID, 2)));

        assertThat(cost.basis()).isEqualTo(CostBasis.LISTED);
        assertThat(cost.amount()).isEqualByComparingTo("6000");
    }

    @Test
    void testListedWithNullProductPriceGivesNullAmount() {
        givenPurchase(PRODUCT_ID);
        givenProducts(product(PRODUCT_ID, null));

        CostBasisResolver.LineCost cost = resolver.resolve(line(), List.of(expanded(PRODUCT_ID, 2)));

        assertThat(cost.basis()).isEqualTo(CostBasis.LISTED);
        // 0 이 아니다 — 원가 0 은 손익을 통째로 망친다.
        assertThat(cost.amount()).isNull();
    }

    // --- BOM 합산 ---

    @Test
    void testAmountIsSumOverBom() {
        givenPurchase(PRODUCT_ID, purchase("4000", ORDERED_ON.minusDays(3)));
        givenPurchase(OTHER_PRODUCT_ID, purchase("1500", ORDERED_ON.minusDays(1)));
        givenProducts(product(PRODUCT_ID, "9999"), product(OTHER_PRODUCT_ID, "9999"));

        CostBasisResolver.LineCost cost = resolver.resolve(line(),
                List.of(expanded(PRODUCT_ID, 2), expanded(OTHER_PRODUCT_ID, 3)));

        assertThat(cost.basis()).isEqualTo(CostBasis.LATEST);
        assertThat(cost.amount()).isEqualByComparingTo("12500");   // 4000*2 + 1500*3
    }

    @Test
    void testMixedBomTakesWorstBasis() {
        givenPurchase(PRODUCT_ID, purchase("4000", ORDERED_ON.minusDays(3)));
        givenPurchase(OTHER_PRODUCT_ID);
        givenProducts(product(PRODUCT_ID, "9999"), product(OTHER_PRODUCT_ID, "1000"));

        CostBasisResolver.LineCost cost = resolver.resolve(line(),
                List.of(expanded(PRODUCT_ID, 1), expanded(OTHER_PRODUCT_ID, 1)));

        // 하나라도 기준가에 기대면 라인 전체가 그만큼만 믿을 수 있다.
        assertThat(cost.basis()).isEqualTo(CostBasis.LISTED);
        assertThat(cost.amount()).isEqualByComparingTo("5000");
    }

    @Test
    void testProductWithUnknownPriceIsNotCountedAsZero() {
        givenPurchase(PRODUCT_ID, purchase("4000", ORDERED_ON.minusDays(3)));
        givenPurchase(OTHER_PRODUCT_ID);
        givenProducts(product(PRODUCT_ID, "9999"), product(OTHER_PRODUCT_ID, null));

        CostBasisResolver.LineCost cost = resolver.resolve(line(),
                List.of(expanded(PRODUCT_ID, 2), expanded(OTHER_PRODUCT_ID, 5)));

        assertThat(cost.basis()).isEqualTo(CostBasis.LISTED);
        // 모르는 물품은 합계에 0 을 보태지 않고, 아는 만큼만 남는다.
        assertThat(cost.amount()).isEqualByComparingTo("8000");
    }

    // --- 언제 굽는가 ---

    @Test
    void testSnapshotWrittenOnFirstStockOut() {
        givenPurchase(PRODUCT_ID, purchase("4000", ORDERED_ON.minusDays(3)));
        givenProducts(product(PRODUCT_ID, "9999"));
        LocalDateTime before = LocalDateTime.now();

        resolver.snapshot(line(), List.of(expanded(PRODUCT_ID, 3)));

        ArgumentCaptor<OrderLine> saved = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(saved.capture());
        assertThat(saved.getValue().getCostBasis()).isEqualTo(CostBasis.LATEST);
        assertThat(saved.getValue().getCostAmount()).isEqualByComparingTo("12000");
        assertThat(saved.getValue().getCostSnapshotAt()).isAfterOrEqualTo(before);
    }

    @Test
    void testSnapshotNotOverwrittenOnSecondStockOut() {
        resolver.snapshot(line(CostBasis.LATEST), List.of(expanded(PRODUCT_ID, 3)));

        // 부분 출고의 나머지에서 다시 구우면 같은 라인의 원가가 출고 횟수만큼 흔들린다.
        verify(orderLineRepository, never()).save(any(OrderLine.class));
        verify(purchaseRecordRepository, never()).findLatestPriced(any(), any(), any(), any());
    }
}
