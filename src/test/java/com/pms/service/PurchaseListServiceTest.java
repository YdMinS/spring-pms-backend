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
import com.pms.domain.Seller;
import com.pms.config.CoupangProperties;
import com.pms.domain.ShoppingListItem;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.PurchaseLine;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.dto.response.PurchaseProductGroup;
import com.pms.dto.response.PurchaseRecordResult;
import com.pms.dto.response.PurchaseRecordView;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.service.stock.StockLedgerService;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PurchaseListServiceImpl 단위 테스트 — BOM 전개·멱등 추출·물품 단위 집계·입고 2원장·미매핑·수동 누적.
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
    @Mock private SellerRepository sellerRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private StockLedgerService stockLedgerService;
    @Mock private CoupangProperties coupangProperties;

    @InjectMocks private PurchaseListServiceImpl service;

    private static final Long PRODUCT_ID = 100L;
    private static final Long SELLER_ID = 3L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);

    private static final Seller SELLER_A =
            Seller.builder().id(SELLER_ID).sellerName("A상사").build();
    private static final Seller SELLER_B =
            Seller.builder().id(9L).sellerName("B상사").build();
    private static final MarketplaceAccount ACCOUNT =
            MarketplaceAccount.builder().id(1L).seller(SELLER_A).platform(Platform.COUPANG).build();
    private static final MarketplaceAccount ACCOUNT_B =
            MarketplaceAccount.builder().id(2L).seller(SELLER_B).platform(Platform.NAVER).build();

    private Product product(Long id, String name) {
        return Product.builder().id(id).productName(name).build();
    }

    private OrderLine paidLine(Long id, int orderQty) {
        return paidLine(id, orderQty, ACCOUNT);
    }

    private OrderLine paidLine(Long id, int orderQty, MarketplaceAccount account) {
        return OrderLine.builder()
                .id(id)
                .order(Order.builder().id(1000L + id).marketplaceAccount(account)
                        .platform(account.getPlatform()).externalOrderId("O" + id).build())
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

    private PurchaseRecord record(Long id, Product p, Seller seller, int quantity, LocalDate on) {
        return PurchaseRecord.builder().id(id).product(p).seller(seller)
                .purchasedOn(on).quantity(quantity).reflectToBasePrice(true).build();
    }

    /** 조회 경로가 매번 부르는 3가지: 물품명 맵·채널 라벨 맵·미매핑 집계용 주문 라인. */
    private void stubLookups(List<Product> products, List<MarketplaceAccount> accounts) {
        given(productRepository.findAllById(any())).willReturn(products);
        if (!accounts.isEmpty()) {
            given(marketplaceAccountRepository.findAllById(any())).willReturn(accounts);
        }
    }

    // --- 추출 (PLAN 2609_29 D11) ---

    @Test
    void extract_BOM전개_옵션당구성수량만큼_autoQty계산() {
        OrderLine line = paidLine(10L, 3);                   // 발주가능 3
        Product a = product(PRODUCT_ID, "A");
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

        service.extract();

        verify(shoppingListItemRepository).resetAllAutoQty();
        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ShoppingListItem::getAutoQty)
                .containsExactly(6, 3);
    }

    /**
     * 결함 ② 회귀: 리셋은 전체인데 재적재만 판매자 스코프였다 → 다른 판매자 라인이 0 으로 남았다.
     * 판매자 파라미터가 사라져 두 범위가 구조적으로 일치한다(D11).
     */
    @Test
    void testExtractResetsAndReloadsAll() {
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class)))
                .willReturn(List.of());

        service.extract();

        verify(shoppingListItemRepository).resetAllAutoQty();
        verify(orderLineRepository).findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class));
    }

    @Test
    void extract_기존라인_manualQty보존_auto만갱신() {
        OrderLine line = paidLine(10L, 3);                   // 발주가능 3
        Product a = product(PRODUCT_ID, "A");
        ProductListingOption option = ProductListingOption.builder().id(1L).platformOptionId("OPT1").build();
        ShoppingListItem existing = ShoppingListItem.builder()
                .id(5L).orderLine(line).product(a).autoQty(0).manualQty(4).build();

        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of(line));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(List.of(10L))).willReturn(List.of(mirror(line, "OPT1")));
        given(productListingOptionRepository.findByPlatformOptionId("OPT1")).willReturn(Optional.of(option));
        given(productListingProductRepository.findByProductListingOptionId(1L)).willReturn(List.of(
                ProductListingProduct.builder().id(1L).product(a).quantity(2).build()    // 3×2 = 6
        ));
        given(shoppingListItemRepository.findByOrderLine_IdAndProduct_Id(10L, PRODUCT_ID))
                .willReturn(Optional.of(existing));

        service.extract();

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

        service.extract();

        verify(shoppingListItemRepository).resetAllAutoQty();
        verify(shoppingListItemRepository, never()).save(any());
    }

    // --- 집계 (PLAN 2609_29 D6·D7·D8) ---

    @Test
    void getList_잔여계산_잔여있으면그룹포함() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 8)).product(a).autoQty(8).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of(record(1L, a, SELLER_A, 5, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseListResponse res = service.getList();

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).neededQty()).isEqualTo(8);
        assertThat(res.items().get(0).purchasedQty()).isEqualTo(5);
        assertThat(res.items().get(0).remainingQty()).isEqualTo(3);
    }

    @Test
    void getList_잔여0이면그룹제외() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 5)).product(a).autoQty(5).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of(record(1L, a, SELLER_A, 5, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        assertThat(service.getList().items()).isEmpty();
    }

    /** D6: 판매자가 달라도 그룹은 물품 하나다 — "얼마를 더 사야 하나"가 흩어지면 안 된다. */
    @Test
    void testGroupKeyIsProductOnly() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem fromA = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 3)).product(a).autoQty(3).manualQty(0).build();
        ShoppingListItem fromB = ShoppingListItem.builder()
                .id(2L).orderLine(paidLine(11L, 5, ACCOUNT_B)).product(a).autoQty(5).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(fromA, fromB));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of());
        stubLookups(List.of(a), List.of(ACCOUNT, ACCOUNT_B));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        List<PurchaseProductGroup> groups = service.getList().items();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).neededQty()).isEqualTo(8);       // 3 + 5
        assertThat(groups.get(0).lines()).hasSize(2);
    }

    /** D6: 구매수량도 전체 기준 — A상사 구매분과 B상사 구매분을 합산한다. */
    @Test
    void testPurchasedQtyIsProductWide() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 10)).product(a).autoQty(10).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of(
                record(1L, a, SELLER_A, 4, TODAY),
                record(2L, a, SELLER_B, 3, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseProductGroup group = service.getList().items().get(0);

        assertThat(group.purchasedQty()).isEqualTo(7);           // 4 + 3
        assertThat(group.remainingQty()).isEqualTo(3);
    }

    /** D8: 주문 라인은 채널 3필드를 갖고, 수동 라인은 셋 다 null 이다(화면이 "수동"으로 그린다). */
    @Test
    void testLineHasChannelFields() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem ordered = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 3)).product(a).autoQty(3).manualQty(0).build();
        ShoppingListItem manual = ShoppingListItem.builder()
                .id(2L).orderLine(null).product(a).autoQty(0).manualQty(2).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(ordered, manual));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of());
        stubLookups(List.of(a), List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        List<PurchaseLine> lines = service.getList().items().get(0).lines();

        assertThat(lines).extracting(PurchaseLine::source, PurchaseLine::marketplaceAccountId,
                        PurchaseLine::sellerName, PurchaseLine::platform, PurchaseLine::neededQty)
                .containsExactly(
                        tuple("ORDER", 1L, "A상사", "COUPANG", 3),
                        tuple("MANUAL", null, null, null, 2));
    }

    /** D8 ④: 완료 판정의 기간 재료가 라인 기록이 아니라 물품 기록이 됐다. 판정 조건 자체는 그대로다(D21). */
    @Test
    void testCompletedUsesProductRecords() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 5)).product(a).autoQty(5).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of(record(1L, a, SELLER_A, 5, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT));

        assertThat(service.getCompletedList(TODAY.minusDays(1), TODAY.plusDays(1))).hasSize(1);
        assertThat(service.getCompletedList(TODAY.plusDays(5), TODAY.plusDays(9))).isEmpty();
    }

    @Test
    void getList_결제완료인데옵션미매핑_unmappedOrders집계() {
        OrderLine line = paidLine(10L, 5);
        given(shoppingListItemRepository.findAll()).willReturn(List.of());
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of(line));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(List.of(10L))).willReturn(List.of(mirror(line, "X")));
        given(productListingOptionRepository.findByPlatformOptionId("X")).willReturn(Optional.empty());

        PurchaseListResponse res = service.getList();

        assertThat(res.items()).isEmpty();
        assertThat(res.unmappedOrders()).hasSize(1);
        assertThat(res.unmappedOrders().get(0).externalItemId()).isEqualTo("X");
        assertThat(res.unmappedOrders().get(0).purchasableQty()).isEqualTo(5);
        assertThat(res.unmappedOrders().get(0).orderCount()).isEqualTo(1);
    }

    @Test
    void addManual_기존수동라인존재_manualQty누적_새행안만듦() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem existing = ShoppingListItem.builder()
                .id(7L).orderLine(null).product(a).autoQty(0).manualQty(4).build();
        given(shoppingListItemRepository.findByOrderLineIsNullAndProduct_Id(PRODUCT_ID))
                .willReturn(Optional.of(existing));

        service.addManual(new ManualItemRequest(PRODUCT_ID, 3));

        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(captor.capture());
        assertThat(captor.getValue().getManualQty()).isEqualTo(7);   // 4 + 3
        verify(productRepository, never()).findById(anyLong());      // 신규 product 조회 없음
    }

    // --- 입고 = 두 원장 (PLAN 2609_29 D1·D16·D17·D19) + 금액 (2609_28 D1~D3) ---

    private PurchaseRecordRequest req(int quantity, String total, String unit,
                                      Boolean reflect, Boolean recordStock) {
        return new PurchaseRecordRequest(PRODUCT_ID, SELLER_ID, TODAY, quantity,
                total == null ? null : new BigDecimal(total),
                unit == null ? null : new BigDecimal(unit),
                reflect, recordStock);
    }

    private void stubAddPurchase() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(PRODUCT_ID, "A")));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(SELLER_A));
        given(purchaseRecordRepository.save(any(PurchaseRecord.class)))
                .willAnswer(inv -> inv.<PurchaseRecord>getArgument(0).toBuilder().id(77L).build());
    }

    /** addPurchase 를 태우고 저장된 PurchaseRecord 를 잡아 온다 — 계산 결과는 save() 인자로만 확인 가능하다. */
    private PurchaseRecord savedPurchase(PurchaseRecordRequest request) {
        stubAddPurchase();

        service.addPurchase(request);

        ArgumentCaptor<PurchaseRecord> captor = ArgumentCaptor.forClass(PurchaseRecord.class);
        verify(purchaseRecordRepository).save(captor.capture());
        return captor.getValue();
    }

    private StockMovementRequest capturedMovement() {
        ArgumentCaptor<StockMovementRequest> captor = ArgumentCaptor.forClass(StockMovementRequest.class);
        verify(stockLedgerService).record(captor.capture());
        return captor.getValue();
    }

    /** D1: [입고] 1회가 돈 원장과 실물 원장을 같은 트랜잭션에서 쓴다. */
    @Test
    void testAddPurchaseRecordsBothLedgers() {
        stubAddPurchase();

        PurchaseRecordResult result = service.addPurchase(req(4, "12000", null, null, null));

        StockMovementRequest movement = capturedMovement();
        assertThat(movement.movementType()).isEqualTo(StockMovementType.STOCK_IN);
        assertThat(movement.reason()).isEqualTo(StockReason.PURCHASE);
        assertThat(movement.productId()).isEqualTo(PRODUCT_ID);
        assertThat(movement.sellerId()).isEqualTo(SELLER_ID);
        assertThat(movement.purchaseRecordId()).isEqualTo(77L);
        assertThat(movement.quantity()).isEqualTo(4);
        assertThat(movement.movedOn()).isEqualTo(TODAY);
        assertThat(result.stockRecorded()).isTrue();
        assertThat(result.purchaseRecordId()).isEqualTo(77L);
    }

    /** D16: 단가 승계 규칙은 원장 하나가 소유한다 — 여기서 계산하면 규칙이 두 벌이 된다. */
    @Test
    void testAddPurchaseDoesNotPassUnitPrice() {
        stubAddPurchase();

        service.addPurchase(req(4, "12000", null, null, null));

        assertThat(capturedMovement().unitPrice()).isNull();
    }

    /** D17: 음수 정정은 "금액을 잘못 적었다"이지 "물건이 나갔다"가 아니다. */
    @Test
    void testAddPurchaseNegativeSkipsStock() {
        stubAddPurchase();

        PurchaseRecordResult result = service.addPurchase(req(-2, "-8000", null, null, null));

        verify(stockLedgerService, never()).record(any());
        assertThat(result.stockRecorded()).isFalse();
    }

    /** D19: 계약은 살아 있다 — 화면이 스위치를 잠갔을 뿐 서버가 무시하면 06 이 붙지 못한다. */
    @Test
    void testAddPurchaseRecordStockFalseSkipsStock() {
        stubAddPurchase();

        PurchaseRecordResult result = service.addPurchase(req(4, "12000", null, null, false));

        verify(purchaseRecordRepository).save(any(PurchaseRecord.class));
        verify(stockLedgerService, never()).record(any());
        assertThat(result.stockRecorded()).isFalse();
    }

    @Test
    void testAddPurchaseRecordStockNullDefaultsTrue() {
        stubAddPurchase();

        assertThat(service.addPurchase(req(4, "12000", null, null, null)).stockRecorded()).isTrue();
        verify(stockLedgerService).record(any());
    }

    @Test
    void testAddPurchaseStampsProductAndSeller() {
        PurchaseRecord saved = savedPurchase(req(3, "12000", null, null, null));

        assertThat(saved.getProduct().getId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getSeller().getId()).isEqualTo(SELLER_ID);
    }

    @Test
    void testAddPurchaseWithTotalDerivesUnitPrice() {
        PurchaseRecord saved = savedPurchase(req(3, "12000", null, null, null));

        assertThat(saved.getTotalAmount()).isEqualByComparingTo("12000.00");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("4000.0000");
    }

    @Test
    void testAddPurchaseWithUnitDerivesTotal() {
        PurchaseRecord saved = savedPurchase(req(3, null, "4000", null, null));

        assertThat(saved.getTotalAmount()).isEqualByComparingTo("12000.00");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("4000.0000");
    }

    /** 요점: 나눠떨어지지 않아도 실지불액(총액)은 흔들리지 않는다(2609_28 D1). */
    @Test
    void testAddPurchaseWithIndivisibleTotalKeepsTotalExact() {
        PurchaseRecord saved = savedPurchase(req(3, "10000", null, null, null));

        assertThat(saved.getTotalAmount()).isEqualByComparingTo("10000.00");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("3333.3333");
    }

    @Test
    void testAddPurchaseWithBothAmountsRejected() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(PRODUCT_ID, "A")));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(SELLER_A));

        assertThatThrownBy(() -> service.addPurchase(req(3, "12000", "4000", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(purchaseRecordRepository, never()).save(any());
        verify(stockLedgerService, never()).record(any());
    }

    @Test
    void testAddPurchaseWithZeroQuantityRejected() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(PRODUCT_ID, "A")));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(SELLER_A));

        assertThatThrownBy(() -> service.addPurchase(req(0, "12000", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(purchaseRecordRepository, never()).save(any());
    }

    /** 정정 행: 수량·금액 모두 음수 → 단가는 양수로 남는다. */
    @Test
    void testAddPurchaseWithNegativeQuantityKeepsPositiveUnitPrice() {
        PurchaseRecord saved = savedPurchase(req(-3, "-12000", null, null, null));

        assertThat(saved.getQuantity()).isEqualTo(-3);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("-12000.00");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("4000.0000");
    }

    @Test
    void testAddPurchaseDefaultsReflectToBasePriceTrue() {
        assertThat(savedPurchase(req(3, "12000", null, null, null)).getReflectToBasePrice()).isTrue();
        assertThat(PurchaseRecord.of(product(PRODUCT_ID, "A"), SELLER_A,
                req(3, "12000", null, false, null)).getReflectToBasePrice()).isFalse();
    }

    /** 금액 미상 행: null 을 0 으로 치환하지 않는다. */
    @Test
    void testAddPurchaseWithoutAmountSaves() {
        PurchaseRecord saved = savedPurchase(req(5, null, null, null, null));

        assertThat(saved.getQuantity()).isEqualTo(5);
        assertThat(saved.getTotalAmount()).isNull();
        assertThat(saved.getUnitPrice()).isNull();
    }

    // --- 최근 구매이력 (PLAN 2609_29 D9) ---

    /** 판매자 무관 조회라 각 줄이 누구 것인지 보여야 한다. */
    @Test
    void testRecentPurchasesIgnoreSeller() {
        Product a = product(PRODUCT_ID, "A");
        given(purchaseRecordRepository.findRecentByProduct(eq(PRODUCT_ID), any())).willReturn(List.of(
                record(1L, a, SELLER_A, 3, TODAY),
                record(2L, a, SELLER_B, 5, TODAY.minusDays(1))));

        List<PurchaseRecordView> views = service.recentPurchases(PRODUCT_ID, 5);

        assertThat(views).extracting(PurchaseRecordView::sellerName)
                .containsExactly("A상사", "B상사");
    }
}
