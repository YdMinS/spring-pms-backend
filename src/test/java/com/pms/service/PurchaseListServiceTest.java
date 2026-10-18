package com.pms.service;

import com.pms.domain.CoupangOrderLine;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.domain.PurchaseCheck;
import com.pms.domain.PurchaseCheckStatus;
import com.pms.domain.PurchaseReceipt;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.config.CoupangProperties;
import com.pms.domain.ShoppingListItem;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.ManualSellerRequest;
import com.pms.dto.request.PurchaseCheckRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.PurchaseLine;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.dto.response.PurchaseProductGroup;
import com.pms.dto.response.PurchaseRecordResult;
import com.pms.dto.response.PurchaseRecordView;
import com.pms.dto.response.PurchaseShare;
import com.pms.exception.BusinessException;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.listing.CellBomResolver;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseCheckRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.service.cost.CostPropagationService;
import com.pms.service.stock.StockLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PurchaseListServiceImpl unit tests — BOM expansion · idempotent extract · (product × seller) share numbers and
 * states · processing records · check-in ledgers · unmapped orders · manual lines.
 * Framework validation (@NotNull and the like) and plain delegation are left to the controller / integration tests.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseListServiceTest {

    @Mock private ShoppingListItemRepository shoppingListItemRepository;
    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private PurchaseCheckRepository purchaseCheckRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private CoupangOrderLineRepository coupangOrderLineRepository;
    @Mock private ElevenstOrderLineRepository elevenstOrderLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private CellBomResolver cellBomResolver;
    @Mock private ProductRepository productRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private StockLedgerService stockLedgerService;
    /** ① 기준가 갱신(2609_28 D4) — 이 테스트는 위임만 확인하고 조건은 CostPropagationServiceImplTest 가 본다. */
    @Mock private CostPropagationService costPropagationService;
    @Mock private PurchaseSettingService purchaseSettingService;
    @Mock private CoupangProperties coupangProperties;
    @Mock private Clock clock;

    @InjectMocks private PurchaseListServiceImpl service;

    private static final Long PRODUCT_ID = 100L;
    private static final Long SELLER_ID = 3L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);
    /** 2026-09-09 12:00 KST — today() of the service is the KST calendar day (FEATURE_2610_09 D67). */
    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");

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

    @BeforeEach
    void fixClock() {
        lenient().when(clock.instant()).thenReturn(NOW);
    }

    private PurchaseCheck check(Long id, Product p, Seller seller, PurchaseCheckStatus status, int neededQty,
                                LocalDate on) {
        return PurchaseCheck.builder().id(id).product(p).seller(seller).status(status)
                .neededQty(neededQty).checkedOn(on).build();
    }

    private ShoppingListItem manualLine(Long id, Product p, Seller seller, int qty) {
        return ShoppingListItem.builder().id(id).orderLine(null).product(p).seller(seller)
                .autoQty(0).manualQty(qty).build();
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
        given(cellBomResolver.forOptions(anyCollection())).willReturn(Map.of(1L,
                CellBomResolver.Bom.of(List.of(
                        new CellBomResolver.Line(1L, a, 2),    // A×2 → 6
                        new CellBomResolver.Line(2L, b, 1))))); // B×1 → 3
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
        given(cellBomResolver.forOptions(anyCollection())).willReturn(Map.of(1L,
                CellBomResolver.Bom.of(List.of(new CellBomResolver.Line(1L, a, 2)))));   // 3×2 = 6
        given(shoppingListItemRepository.findByOrderLine_IdAndProduct_Id(10L, PRODUCT_ID))
                .willReturn(Optional.of(existing));

        service.extract();

        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(captor.capture());
        assertThat(captor.getValue().getAutoQty()).isEqualTo(6);
        assertThat(captor.getValue().getManualQty()).isEqualTo(4);   // 보존
    }

    /** D85: a share that fell from above 0 to 0 during the extract gets a NEED_ZERO record dated today. */
    @Test
    void testExtractRecordsNeedZero() {
        Product a = product(PRODUCT_ID, "A");
        OrderLine line = paidLine(10L, 3);
        ShoppingListItem before = ShoppingListItem.builder()
                .id(1L).orderLine(line).product(a).autoQty(3).manualQty(0).build();
        ShoppingListItem after = before.toBuilder().autoQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(before)).willReturn(List.of(after));
        given(marketplaceAccountRepository.findAllById(any())).willReturn(List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());
        given(purchaseCheckRepository.findByStatusAndCancelledAtIsNull(PurchaseCheckStatus.NEED_ZERO)).willReturn(List.of());
        given(productRepository.getReferenceById(PRODUCT_ID)).willReturn(a);
        given(sellerRepository.getReferenceById(SELLER_ID)).willReturn(SELLER_A);

        service.extract();

        ArgumentCaptor<PurchaseCheck> captor = ArgumentCaptor.forClass(PurchaseCheck.class);
        verify(purchaseCheckRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PurchaseCheckStatus.NEED_ZERO);
        assertThat(captor.getValue().getCheckedOn()).isEqualTo(TODAY);
        assertThat(captor.getValue().getSeller()).isEqualTo(SELLER_A);
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

    // --- Aggregation (PLAN 2609_29 D6 group key · D7 · D8; per-seller quantities: FEATURE_2610_09 D2 · D22) ---

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

        PurchaseListResponse res = service.getList(null);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).neededQty()).isEqualTo(8);
        assertThat(res.items().get(0).purchasedQty()).isEqualTo(5);
        assertThat(res.items().get(0).remainingQty()).isEqualTo(3);
    }

    @Test
    void testGroupCarriesProductBrand() {
        Product a = Product.builder().id(PRODUCT_ID).productName("A").brand("모모").build();
        ShoppingListItem sli = manualLine(1L, a, SELLER_A, 2);

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of());
        stubLookups(List.of(a), List.of());
        given(sellerRepository.findAllById(any())).willReturn(List.of(SELLER_A));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseProductGroup group = service.getList(null).items().get(0);

        assertThat(group.productName()).isEqualTo("A");
        assertThat(group.brand()).isEqualTo("모모");
    }

    /** FEATURE_2610_09 D57: the numbers no longer move a row — fully bought but unchecked stays on the list, tagged. */
    @Test
    void testCoveredButUncheckedStaysOnListWithTargetMet() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 5)).product(a).autoQty(5).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID)))
                .willReturn(List.of(record(1L, a, SELLER_A, 5, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseProductGroup group = service.getList(null).items().get(0);

        assertThat(group.remainingQty()).isZero();
        assertThat(group.shares()).singleElement().satisfies(share -> {
            assertThat(share.state()).isEqualTo("OPEN");
            assertThat(share.targetMet()).isTrue();
        });
    }

    /** D2 · D6: a chosen seller sees only its share — numbers, shares and lines. */
    @Test
    void testSellerFilterNarrowsNumbersAndLines() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem fromA = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 3)).product(a).autoQty(3).manualQty(0).build();
        ShoppingListItem fromB = ShoppingListItem.builder()
                .id(2L).orderLine(paidLine(11L, 5, ACCOUNT_B)).product(a).autoQty(5).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(fromA, fromB));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of(
                record(1L, a, SELLER_A, 1, TODAY),
                record(2L, a, SELLER_B, 7, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT, ACCOUNT_B));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseProductGroup group = service.getList(SELLER_B.getId()).items().get(0);

        assertThat(group.neededQty()).isEqualTo(5);
        assertThat(group.purchasedQty()).isEqualTo(7);
        assertThat(group.remainingQty()).isEqualTo(-2);
        assertThat(group.shares()).extracting(PurchaseShare::sellerId).containsExactly(SELLER_B.getId());
        assertThat(group.lines()).extracting(PurchaseLine::itemId).containsExactly(2L);
    }

    /** D22 · D58: in 「전체」 one seller's surplus never covers another's shortfall, and the three numbers subtract. */
    @Test
    void testAllViewAddsShortfallsOnly() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem fromA = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 5)).product(a).autoQty(5).manualQty(0).build();
        ShoppingListItem fromB = ShoppingListItem.builder()
                .id(2L).orderLine(paidLine(11L, 3, ACCOUNT_B)).product(a).autoQty(3).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(fromA, fromB));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of(
                record(1L, a, SELLER_B, 8, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT, ACCOUNT_B));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseProductGroup group = service.getList(null).items().get(0);

        assertThat(group.neededQty()).isEqualTo(8);       // 5 + 3
        assertThat(group.purchasedQty()).isEqualTo(3);    // min(0, 5) + min(8, 3)
        assertThat(group.remainingQty()).isEqualTo(5);    // A's shortfall only
        assertThat(group.lines()).hasSize(2);
    }

    /** D17: both lines carry the seller; a manual line keeps the channel fields null. */
    @Test
    void testLineHasChannelFields() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem ordered = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 3)).product(a).autoQty(3).manualQty(0).build();
        ShoppingListItem manual = manualLine(2L, a, SELLER_B, 2);

        given(shoppingListItemRepository.findAll()).willReturn(List.of(ordered, manual));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of());
        stubLookups(List.of(a), List.of(ACCOUNT));
        given(sellerRepository.findAllById(any())).willReturn(List.of(SELLER_B));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        List<PurchaseLine> lines = service.getList(null).items().get(0).lines();

        assertThat(lines).extracting(PurchaseLine::source, PurchaseLine::marketplaceAccountId,
                        PurchaseLine::sellerId, PurchaseLine::sellerName, PurchaseLine::platform, PurchaseLine::neededQty)
                .containsExactly(
                        tuple("ORDER", 1L, SELLER_ID, "A상사", "COUPANG", 3),
                        tuple("MANUAL", null, 9L, "B상사", null, 2));
    }

    /** D57 · D63: [매입 완료] moves the share to the completed tab, filtered by the check day. */
    @Test
    void testPurchasedCheckMovesToCompleted() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 5)).product(a).autoQty(5).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of());
        given(purchaseCheckRepository.findByProduct_IdInAndCancelledAtIsNull(List.of(PRODUCT_ID))).willReturn(List.of(
                check(50L, a, SELLER_A, PurchaseCheckStatus.PURCHASED, 5, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        assertThat(service.getList(null).items()).isEmpty();
        List<PurchaseProductGroup> completed = service.getCompletedList(TODAY, TODAY, null);
        assertThat(completed).singleElement().satisfies(group ->
                assertThat(group.shares()).singleElement().satisfies(share -> {
                    assertThat(share.state()).isEqualTo("PURCHASED");
                    assertThat(share.checkId()).isEqualTo(50L);
                    assertThat(share.checkedOn()).isEqualTo(TODAY);
                }));
        assertThat(service.getCompletedList(TODAY.plusDays(1), TODAY.plusDays(2), null)).isEmpty();
    }

    /** D60 · D82: more needed than written on the check → back to the list, out of the completed tab. */
    @Test
    void testCheckLapsesWhenNeedGrows() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 7)).product(a).autoQty(7).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of());
        given(purchaseCheckRepository.findByProduct_IdInAndCancelledAtIsNull(List.of(PRODUCT_ID))).willReturn(List.of(
                check(50L, a, SELLER_A, PurchaseCheckStatus.NOT_NEEDED, 5, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        assertThat(service.getList(null).items()).hasSize(1);
        assertThat(service.getCompletedList(null, null, null)).isEmpty();
    }

    /** D67: 「내일 매입」 sits in 「오늘 넘김」 on its check day and is back on the list the next day. */
    @Test
    void testTomorrowCountsOnItsDayOnly() {
        Product a = product(PRODUCT_ID, "A");
        Product b = product(200L, "B");
        ShoppingListItem lineA = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 2)).product(a).autoQty(2).manualQty(0).build();
        ShoppingListItem lineB = ShoppingListItem.builder()
                .id(2L).orderLine(paidLine(11L, 2)).product(b).autoQty(2).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(lineA, lineB));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID, 200L))).willReturn(List.of());
        given(purchaseCheckRepository.findByProduct_IdInAndCancelledAtIsNull(List.of(PRODUCT_ID, 200L))).willReturn(List.of(
                check(50L, a, SELLER_A, PurchaseCheckStatus.TOMORROW, 2, TODAY),
                check(51L, b, SELLER_A, PurchaseCheckStatus.TOMORROW, 2, TODAY.minusDays(1))));
        stubLookups(List.of(a, b), List.of(ACCOUNT));
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of());

        PurchaseListResponse res = service.getList(null);

        assertThat(res.skippedToday()).extracting(PurchaseProductGroup::productId).containsExactly(PRODUCT_ID);
        assertThat(res.skippedToday().get(0).shares().get(0).checkId()).isEqualTo(50L);
        assertThat(res.items()).extracting(PurchaseProductGroup::productId).containsExactly(200L);
    }

    /** D78 · D81: 「전체」 completes a row only when every share is processed; the row's day is the latest one. */
    @Test
    void testAllViewNeedsEveryShareProcessed() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem fromA = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 3)).product(a).autoQty(3).manualQty(0).build();
        ShoppingListItem fromB = ShoppingListItem.builder()
                .id(2L).orderLine(paidLine(11L, 4, ACCOUNT_B)).product(a).autoQty(4).manualQty(0).build();
        PurchaseCheck doneA = check(50L, a, SELLER_A, PurchaseCheckStatus.PURCHASED, 3, TODAY.minusDays(2));

        given(shoppingListItemRepository.findAll()).willReturn(List.of(fromA, fromB));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID))).willReturn(List.of());
        given(purchaseCheckRepository.findByProduct_IdInAndCancelledAtIsNull(List.of(PRODUCT_ID)))
                .willReturn(List.of(doneA))
                .willReturn(List.of(doneA, check(51L, a, SELLER_B, PurchaseCheckStatus.NOT_NEEDED, 4, TODAY)));
        stubLookups(List.of(a), List.of(ACCOUNT, ACCOUNT_B));

        assertThat(service.getCompletedList(null, null, null)).isEmpty();          // B still open
        List<PurchaseProductGroup> completed = service.getCompletedList(TODAY, TODAY, null);
        assertThat(completed).singleElement().satisfies(group ->
                assertThat(group.shares()).extracting(PurchaseShare::state)
                        .containsExactly("PURCHASED", "NOT_NEEDED"));
    }

    /** D80: a share whose need has always been 0 is shown nowhere; one with a NEED_ZERO record is completed. */
    @Test
    void testZeroShareShownOnlyWithNeedZeroRecord() {
        Product a = product(PRODUCT_ID, "A");
        Product b = product(200L, "B");
        ShoppingListItem zeroA = ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 2)).product(a).autoQty(0).manualQty(0).build();
        ShoppingListItem zeroB = ShoppingListItem.builder()
                .id(2L).orderLine(paidLine(11L, 2)).product(b).autoQty(0).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(zeroA, zeroB));
        given(purchaseRecordRepository.findByProduct_IdIn(List.of(PRODUCT_ID, 200L))).willReturn(List.of());
        given(purchaseCheckRepository.findByProduct_IdInAndCancelledAtIsNull(List.of(PRODUCT_ID, 200L))).willReturn(List.of(
                check(50L, b, SELLER_A, PurchaseCheckStatus.NEED_ZERO, 0, TODAY)));
        stubLookups(List.of(a, b), List.of(ACCOUNT));

        assertThat(service.getCompletedList(null, null, null))
                .extracting(PurchaseProductGroup::productId).containsExactly(200L);
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

    /** FEATURE_2610_17 / D17: an 11st line without an option is grouped under its 11st option number. */
    @Test
    void getList_elevenstLineWithoutOption_unmappedUnderPrdStckNo() {
        OrderLine line = paidLine(11L, 2);
        given(shoppingListItemRepository.findAll()).willReturn(List.of());
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of(line));
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(List.of(11L))).willReturn(List.of(
                ElevenstOrderLine.builder().id(1L).orderLine(line).ordNo("O11").ordPrdSeq("1")
                        .prdStckNo("STK-9").build()));

        PurchaseListResponse res = service.getList(null);

        assertThat(res.unmappedOrders()).hasSize(1);
        assertThat(res.unmappedOrders().get(0).externalItemId()).isEqualTo("STK-9");
        assertThat(res.unmappedOrders().get(0).purchasableQty()).isEqualTo(2);
        verify(productListingOptionRepository, never()).findByPlatformOptionId(any());
    }

    /** D17: the line's own option column is read first — no vendorItemId lookup for it. */
    @Test
    void extract_usesLineOptionColumnFirst() {
        Product a = product(PRODUCT_ID, "A");
        ProductListingOption option = ProductListingOption.builder().id(70L).build();
        OrderLine line = paidLine(12L, 3).toBuilder().productListingOption(option).build();
        given(orderLineRepository.findRecentByStatus(eq(OrderStatus.PAID), any(LocalDateTime.class))).willReturn(List.of(line));
        given(cellBomResolver.forOptions(List.of(option))).willReturn(Map.of(70L,
                new CellBomResolver.Bom(List.of(new CellBomResolver.Line(1L, a, 2)), false)));
        given(shoppingListItemRepository.findByOrderLine_IdAndProduct_Id(12L, PRODUCT_ID)).willReturn(Optional.empty());

        service.extract();

        ArgumentCaptor<ShoppingListItem> saved = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(saved.capture());
        assertThat(saved.getValue().getAutoQty()).isEqualTo(6);
        verify(productListingOptionRepository, never()).findByPlatformOptionId(any());
    }

    @Test
    void addManual_기존수동라인존재_manualQty누적_새행안만듦() {
        Product a = product(PRODUCT_ID, "A");
        ShoppingListItem existing = manualLine(7L, a, SELLER_A, 4);
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(SELLER_A));
        given(shoppingListItemRepository.findByOrderLineIsNullAndProduct_IdAndSeller_Id(PRODUCT_ID, SELLER_ID))
                .willReturn(Optional.of(existing));

        service.addManual(new ManualItemRequest(PRODUCT_ID, SELLER_ID, 3));

        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(captor.capture());
        assertThat(captor.getValue().getManualQty()).isEqualTo(7);   // 4 + 3
        verify(productRepository, never()).findById(anyLong());      // 신규 product 조회 없음
    }

    /** D14 · D26: moving a manual line onto a seller that already has one is refused — never merged. */
    @Test
    void testChangeManualSellerConflictIs409() {
        Product a = product(PRODUCT_ID, "A");
        given(shoppingListItemRepository.findById(7L)).willReturn(Optional.of(manualLine(7L, a, SELLER_A, 2)));
        given(sellerRepository.findById(9L)).willReturn(Optional.of(SELLER_B));
        given(shoppingListItemRepository.findByOrderLineIsNullAndProduct_IdAndSeller_Id(PRODUCT_ID, 9L))
                .willReturn(Optional.of(manualLine(8L, a, SELLER_B, 5)));

        assertThatThrownBy(() -> service.changeManualSeller(7L, new ManualSellerRequest(9L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("B상사 몫 수동 줄이 이미 있습니다. 그 줄의 수량을 고치세요.");
        verify(shoppingListItemRepository, never()).save(any());
    }

    /** D23 · D77: order lines cannot be edited, moved or deleted from the purchase list. */
    @Test
    void testManualOperationsRejectOrderLine() {
        Product a = product(PRODUCT_ID, "A");
        given(shoppingListItemRepository.findById(1L)).willReturn(Optional.of(ShoppingListItem.builder()
                .id(1L).orderLine(paidLine(10L, 3)).product(a).autoQty(3).manualQty(0).build()));

        assertThatThrownBy(() -> service.adjustManual(1L, new ManualAdjustRequest(2)))
                .isInstanceOf(BusinessException.class).hasMessage("수동 줄만 바꿀 수 있습니다.");
        assertThatThrownBy(() -> service.deleteManual(1L))
                .isInstanceOf(BusinessException.class).hasMessage("수동 줄만 바꿀 수 있습니다.");
        verify(shoppingListItemRepository, never()).delete(any());
    }

    /** D62: a new processing record cancels the share's previous screen-made one and keeps the current need. */
    @Test
    void testCheckCancelsPreviousAndWritesNeed() {
        Product a = product(PRODUCT_ID, "A");
        PurchaseCheck previous = check(40L, a, SELLER_A, PurchaseCheckStatus.TOMORROW, 3, TODAY);
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(a));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(SELLER_A));
        given(purchaseCheckRepository.findByProduct_IdAndSeller_IdAndCancelledAtIsNull(PRODUCT_ID, SELLER_ID))
                .willReturn(List.of(previous));
        given(shoppingListItemRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(manualLine(7L, a, SELLER_A, 4)));

        service.check(new PurchaseCheckRequest(PRODUCT_ID, SELLER_ID, PurchaseCheckStatus.PURCHASED));

        ArgumentCaptor<PurchaseCheck> captor = ArgumentCaptor.forClass(PurchaseCheck.class);
        verify(purchaseCheckRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getId()).isEqualTo(40L);
        assertThat(captor.getAllValues().get(0).getCancelledAt()).isNotNull();
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(PurchaseCheckStatus.PURCHASED);
        assertThat(captor.getAllValues().get(1).getNeededQty()).isEqualTo(4);
        assertThat(captor.getAllValues().get(1).getCheckedOn()).isEqualTo(TODAY);
    }

    /** D85: NEED_ZERO is written by the extract only — the screen can neither send nor cancel it. */
    @Test
    void testNeedZeroIsNotScreenMade() {
        assertThatThrownBy(() -> service.check(
                new PurchaseCheckRequest(PRODUCT_ID, SELLER_ID, PurchaseCheckStatus.NEED_ZERO)))
                .isInstanceOf(BusinessException.class);
        given(purchaseCheckRepository.findById(50L)).willReturn(Optional.of(
                check(50L, product(PRODUCT_ID, "A"), SELLER_A, PurchaseCheckStatus.NEED_ZERO, 0, TODAY)));
        assertThatThrownBy(() -> service.cancelCheck(50L)).isInstanceOf(BusinessException.class);
        verify(purchaseCheckRepository, never()).save(any());
    }

    // --- Intake (PLAN 2609_29 D1·D16·D17; stock ledger only when linked: FEATURE_2610_09 D65) + amounts (2609_28 D1~D3) ---

    private PurchaseRecordRequest req(int quantity, String total, String unit,
                                      Boolean reflect, Boolean recordStock) {
        return new PurchaseRecordRequest(PRODUCT_ID, SELLER_ID, TODAY, quantity,
                total == null ? null : new BigDecimal(total),
                unit == null ? null : new BigDecimal(unit),
                reflect, recordStock);
    }

    private void stubAddPurchase() {
        stubAddPurchase(true);
    }

    private void stubAddPurchase(boolean stockLinked) {
        given(purchaseSettingService.isStockLinked()).willReturn(stockLinked);
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

    /** 2609_28 D4 ①: 매입 저장 직후 기준가 갱신에 위임한다(조건 판정은 그 서비스가 소유). */
    @Test
    void testAddPurchaseDelegatesBasePriceUpdate() {
        stubAddPurchase();

        service.addPurchase(req(4, "12000", null, null, null));

        ArgumentCaptor<PurchaseRecord> captor = ArgumentCaptor.forClass(PurchaseRecord.class);
        verify(costPropagationService).updateBasePrice(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(77L);   // 저장된 행이 그대로 넘어간다
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

    /** D65 · D84: stock link off → purchase record only, and the record remembers it. */
    @Test
    void testAddPurchaseStockLinkOffSkipsStock() {
        stubAddPurchase(false);

        PurchaseRecordResult result = service.addPurchase(req(4, "12000", null, null, null));

        ArgumentCaptor<PurchaseRecord> captor = ArgumentCaptor.forClass(PurchaseRecord.class);
        verify(purchaseRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStockLinked()).isFalse();
        verify(stockLedgerService, never()).record(any());
        assertThat(result.stockRecorded()).isFalse();
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

    /** D7: sellerId null = every seller, so each row must show whose it is. */
    @Test
    void testRecentPurchasesIgnoreSeller() {
        Product a = product(PRODUCT_ID, "A");
        given(purchaseRecordRepository.findRecentByProduct(eq(PRODUCT_ID), isNull(), any())).willReturn(List.of(
                record(1L, a, SELLER_A, 3, TODAY),
                record(2L, a, SELLER_B, 5, TODAY.minusDays(1))));

        List<PurchaseRecordView> views = service.recentPurchases(PRODUCT_ID, null, 5);

        assertThat(views).extracting(PurchaseRecordView::sellerName)
                .containsExactly("A상사", "B상사");
    }

    // --- receipt check-in (FEATURE_2610_18) ---

    /** D93 ①: a receipt line keeps its receipt; the check-in rules stay the same (stock follows the setting). */
    @Test
    void testAddPurchaseWithReceiptLinksRecord() {
        stubAddPurchase();
        PurchaseReceipt receipt = PurchaseReceipt.builder().id(5L).build();

        PurchaseRecordResult result = service.addPurchase(req(4, "12000", null, null, null), receipt);

        ArgumentCaptor<PurchaseRecord> captor = ArgumentCaptor.forClass(PurchaseRecord.class);
        verify(purchaseRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getReceipt()).isSameAs(receipt);
        assertThat(result.stockRecorded()).isTrue();
    }

    /** D93 ①: the history row carries the receipt id only when the purchase came with a receipt. */
    @Test
    void testRecentPurchasesCarryReceiptId() {
        Product a = product(PRODUCT_ID, "A");
        PurchaseRecord withReceipt = record(1L, a, SELLER_A, 3, TODAY).toBuilder()
                .receipt(PurchaseReceipt.builder().id(5L).build()).build();
        given(purchaseRecordRepository.findRecentByProduct(eq(PRODUCT_ID), isNull(), any())).willReturn(List.of(
                withReceipt,
                record(2L, a, SELLER_A, 5, TODAY)));

        List<PurchaseRecordView> views = service.recentPurchases(PRODUCT_ID, null, 5);

        assertThat(views).extracting(PurchaseRecordView::receiptId).containsExactly(5L, null);
    }
}
