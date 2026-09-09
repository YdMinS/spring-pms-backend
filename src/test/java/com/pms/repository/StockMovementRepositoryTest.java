package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.StockLocation;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.response.PurchaseCandidateView;
import com.pms.dto.response.ReturnCandidateView;
import com.pms.dto.response.StockBalanceView;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * StockMovementRepository — the aggregate and the two "waiting to be checked in" lists.
 *
 * <p>These queries cannot be verified with mocks: what matters is the SQL itself (SUM aggregation,
 * correlated sub-queries, the seller grouping and above all the LEFT join on the claim). An inner
 * join would silently delete order-unmatched claims from the picker, and then the goods that
 * physically came back could never be entered.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class StockMovementRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);

    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private TestEntityManager em;

    private MarketplaceAccount account;
    private Seller seller;

    @BeforeEach
    void setUp() {
        seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
    }

    // ---------------------------------------------------------------- balances

    @Test
    void findBalances_sumsLedgerPerProduct() {
        Product product = product("양말A");
        movement(product, StockMovementType.STOCK_IN, 5);
        movement(product, StockMovementType.DISPOSAL, -2);
        em.flush();
        em.clear();

        List<StockBalanceView> balances = stockMovementRepository.findBalances(null, null, null);

        assertThat(balances).singleElement()
                .extracting(StockBalanceView::productName, StockBalanceView::onHand)
                .containsExactly("양말A", 3L);
    }

    @Test
    void findBalances_allowsNegativeOnHand() {
        Product product = product("장갑B");
        movement(product, StockMovementType.DISPOSAL, -1);
        em.flush();
        em.clear();

        // A negative balance means "an entry is missing" — it is a signal, not something to hide.
        assertThat(stockMovementRepository.findBalances(null, null, null))
                .singleElement().extracting(StockBalanceView::onHand).isEqualTo(-1L);
    }

    /** 🔴 Stock is not shared between sellers (2609_29 D4·D5): one product, two sellers, two rows. */
    @Test
    void findBalances_splitsBySeller() {
        Product product = product("양말A");
        Seller other = em.persist(Seller.builder()
                .sellerName("셀러B").businessRegistration("222-22-22222").build());
        movement(product, seller, StockMovementType.STOCK_IN, 5);
        movement(product, other, StockMovementType.STOCK_IN, 2);
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findBalances(null, null, null))
                .extracting(StockBalanceView::sellerName, StockBalanceView::onHand)
                .containsExactlyInAnyOrder(tuple("셀러A", 5L), tuple("셀러B", 2L));
    }

    @Test
    void findBalances_filtersBySeller() {
        Product product = product("양말A");
        Seller other = em.persist(Seller.builder()
                .sellerName("셀러B").businessRegistration("222-22-22222").build());
        movement(product, seller, StockMovementType.STOCK_IN, 5);
        movement(product, other, StockMovementType.STOCK_IN, 2);
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findBalances(null, other.getId(), null))
                .singleElement().extracting(StockBalanceView::onHand).isEqualTo(2L);
    }

    @Test
    void findBalances_filtersByKeyword() {
        movement(product("양말A"), StockMovementType.STOCK_IN, 1);
        movement(product("장갑B"), StockMovementType.STOCK_IN, 1);
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findBalances(null, null, "양말"))
                .singleElement().extracting(StockBalanceView::productName).isEqualTo("양말A");
    }

    // ---------------------------------------------------- purchase candidates

    @Test
    void findPurchaseCandidates_excludesFullyReceived() {
        Product product = product("양말A");
        PurchaseRecord record = purchase(product, 3, new BigDecimal("4000"));
        received(product, record, 3);
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findPurchaseCandidates(null)).isEmpty();
    }

    @Test
    void findPurchaseCandidates_showsPartialRemaining() {
        Product product = product("양말A");
        PurchaseRecord record = purchase(product, 3, new BigDecimal("4000"));
        received(product, record, 2);
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findPurchaseCandidates(null)).singleElement()
                .extracting(PurchaseCandidateView::purchasedQty, PurchaseCandidateView::receivedQty,
                        PurchaseCandidateView::remainingQty)
                .containsExactly(3, 2, 1);
    }

    @Test
    void findPurchaseCandidates_excludesCorrectionRows() {
        Product product = product("양말A");
        purchase(product, -2, new BigDecimal("4000"));   // reversed purchase
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findPurchaseCandidates(null)).isEmpty();
    }

    /**
     * 🔴 Regression for D3·D20: the query must stand on the purchase ledger alone. A purchase has no
     * order at all any more, and the row still has to be pickable — with its seller as identity.
     */
    @Test
    void findPurchaseCandidates_standsOnPurchaseRecordAlone() {
        Product product = product("양말A");
        purchase(product, 4, new BigDecimal("4000"));
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findPurchaseCandidates(null)).singleElement()
                .extracting(PurchaseCandidateView::remainingQty, PurchaseCandidateView::sellerName)
                .containsExactly(4, "셀러A");
    }

    @Test
    void findPurchaseCandidates_keepsUnknownAmount() {
        Product product = product("양말A");
        purchase(product, 2, null);
        em.flush();
        em.clear();

        // Goods can arrive before the receipt is known — the row must stay pickable.
        assertThat(stockMovementRepository.findPurchaseCandidates(null)).singleElement()
                .extracting(PurchaseCandidateView::unitPrice).isNull();
    }

    // ------------------------------------------------------ return candidates

    @Test
    void findReturnCandidates_ignoresCollectStatus() {
        claim(orderLine(), 1, "AnyUnknownCollectStatus");
        em.flush();
        em.clear();

        // 🔴 The claim status mapping is unverified against a live account: filtering by it would
        //    block checking in goods that physically came back.
        assertThat(stockMovementRepository.findReturnCandidates()).hasSize(1);
    }

    @Test
    void findReturnCandidates_showsRemaining() {
        OrderClaim claim = claim(orderLine(), 2, "CompleteCollect");
        returned(product("양말A"), claim, 1);
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findReturnCandidates()).singleElement()
                .extracting(ReturnCandidateView::claimQty, ReturnCandidateView::receivedQty,
                        ReturnCandidateView::remainingQty, ReturnCandidateView::claimStatus)
                .containsExactly(2, 1, 1, ClaimStatus.RECEIVED.name());
    }

    @Test
    void findReturnCandidates_excludesFullyReceived() {
        OrderClaim claim = claim(orderLine(), 2, "CompleteCollect");
        returned(product("양말A"), claim, 2);
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findReturnCandidates()).isEmpty();
    }

    /** Regression: {@code order_claim.order_line} is nullable when order matching failed. */
    @Test
    void findReturnCandidates_includesUnmatchedOrderLine() {
        claim(null, 1, "BeforeDirection");
        em.flush();
        em.clear();

        assertThat(stockMovementRepository.findReturnCandidates()).singleElement()
                .extracting(ReturnCandidateView::orderLineId, ReturnCandidateView::itemName)
                .containsExactly(null, "반품상품");
    }

    // ------------------------------------------------------------- fixtures

    private Product product(String name) {
        return em.persist(Product.builder().productName(name).price(new BigDecimal("1000")).build());
    }

    private OrderLine orderLine() {
        Order order = em.persist(Order.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("O-1").orderedAt(LocalDateTime.of(2026, 9, 1, 10, 0)).build());
        return em.persist(OrderLine.builder()
                .order(order).status(OrderStatus.PAID).itemName("양말세트")
                .orderQty(3).cancelQty(0).holdQty(0).build());
    }

    private PurchaseRecord purchase(Product product, int quantity, BigDecimal unitPrice) {
        return em.persist(PurchaseRecord.builder()
                .product(product).seller(seller).purchasedOn(DAY).quantity(quantity)
                .totalAmount(unitPrice == null ? null : unitPrice.multiply(BigDecimal.valueOf(quantity)))
                .unitPrice(unitPrice).reflectToBasePrice(true).build());
    }

    private OrderClaim claim(OrderLine line, int quantity, String collectStatus) {
        return em.persist(OrderClaim.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .claimType(ClaimType.RETURN).externalClaimId("C-" + quantity + collectStatus)
                .externalOrderId("O-1").externalItemId("V-1").orderLine(line)
                .orderItemMatchAttempts(0).itemName("반품상품").quantity(quantity)
                .status(ClaimStatus.RECEIVED).platformStatus("RETURNS_UNCHECKED")
                .collectStatus(collectStatus)
                .receivedAt(LocalDateTime.of(2026, 9, 5, 9, 0))
                .syncedAt(LocalDateTime.of(2026, 9, 5, 9, 5)).build());
    }

    private void movement(Product product, StockMovementType type, int quantity) {
        movement(product, seller, type, quantity);
    }

    private void movement(Product product, Seller owner, StockMovementType type, int quantity) {
        em.persist(StockMovement.builder()
                .product(product).seller(owner).movementType(type).quantity(quantity)
                .location(StockLocation.OWN)
                .reason(type == StockMovementType.STOCK_IN ? StockReason.FREE : StockReason.DAMAGED)
                .movedOn(DAY).createdBy("admin@test.com").build());
    }

    private void received(Product product, PurchaseRecord record, int quantity) {
        em.persist(StockMovement.builder()
                .product(product).seller(seller).movementType(StockMovementType.STOCK_IN).quantity(quantity)
                .location(StockLocation.OWN).reason(StockReason.PURCHASE).purchaseRecord(record)
                .movedOn(DAY).createdBy("admin@test.com").build());
    }

    private void returned(Product product, OrderClaim claim, int quantity) {
        em.persist(StockMovement.builder()
                .product(product).seller(seller).movementType(StockMovementType.RETURN_IN).quantity(quantity)
                .location(StockLocation.OWN).orderClaim(claim)
                .movedOn(DAY).createdBy("admin@test.com").build());
    }
}
