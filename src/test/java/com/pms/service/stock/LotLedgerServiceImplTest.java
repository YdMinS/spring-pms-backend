package com.pms.service.stock;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.SellerDebt;
import com.pms.domain.StockLocation;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.LotShareUsageRepository;
import com.pms.repository.SellerDebtRepository;
import com.pms.security.crypto.AesAttributeConverter;
import com.pms.service.PurchaseSettingService;
import com.pms.service.sales.SalesStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;

/**
 * LotLedgerServiceImpl on a real ledger (FEATURE_2610_19 D32 ~ D34 · D48 · D50 · D124 · D130 ~ D132 · D136 ·
 * D137 · D139 · D149). The rules live in the order of the rows, so they are checked on H2, not with mocks.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class, LotLedgerServiceImpl.class})
class LotLedgerServiceImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired private LotLedgerService lotLedgerService;
    @Autowired private SellerDebtRepository sellerDebtRepository;
    @Autowired private LotShareUsageRepository lotShareUsageRepository;
    @Autowired private TestEntityManager em;
    @MockBean private PurchaseSettingService purchaseSettingService;
    @MockBean private SalesStatsService salesStatsService;

    private Seller sellerA;
    private Seller sellerB;
    private MarketplaceAccount accountA;
    private Product product;

    @BeforeEach
    void setUp() {
        given(purchaseSettingService.isStockLinked()).willReturn(true);
        given(salesStatsService.summary(any(), any(), any(), anyBoolean())).willReturn(List.of());
        sellerA = em.persist(Seller.builder().sellerName("A상사").businessRegistration("111-11-11111").build());
        sellerB = em.persist(Seller.builder().sellerName("B상사").businessRegistration("222-22-22222").build());
        accountA = MarketplaceAccountFixture.coupangAccount(em, sellerA);
        product = em.persist(Product.builder().productName("양말").price(new BigDecimal("1000")).build());
    }

    /** D32 · D33: the order seller's own lots go first, oldest first; no debt. */
    @Test
    void allocateOutbound_ownLotsFirstOldestFirst() {
        StockMovement older = lot(sellerA, 2, "900", DAY.minusDays(2));
        StockMovement newer = lot(sellerA, 5, "800", DAY.minusDays(1));
        lot(sellerB, 5, "2000", DAY.minusDays(3));

        List<LotSlice> slices = lotLedgerService.allocateOutbound(product, sellerA, 3, DAY);

        assertThat(slices).extracting(s -> s.source().getId(), LotSlice::quantity)
                .containsExactly(tuple(older.getId(), 2), tuple(newer.getId(), 1));
    }

    /** D34 · D48 ① ③ · D124: own goods first, then the dearest of the others, then a row without a lot. */
    @Test
    void allocateOutbound_borrowsDearestThenNoLot() {
        StockMovement own = lot(sellerA, 1, "900", DAY.minusDays(2));
        lot(sellerB, 1, "1500", DAY.minusDays(3));
        StockMovement dear = lot(sellerB, 1, "2000", DAY.minusDays(1));

        List<LotSlice> slices = lotLedgerService.allocateOutbound(product, sellerA, 3, DAY);

        assertThat(slices).hasSize(3);
        assertThat(slices.get(0).source().getId()).isEqualTo(own.getId());
        assertThat(slices.get(1).source().getId()).isEqualTo(dear.getId());
        assertThat(slices.get(1).seller().getId()).isEqualTo(sellerB.getId());
        assertThat(slices.get(2).source().getId()).isNotNull();
        assertThat(lotLedgerService.allocateOutbound(product, sellerA, 4, DAY).get(3).source()).isNull();
    }

    /** D33 · D49 · D137 · D136: a borrowed lot without a price takes the lender's dearest priced lot left, else 「미기입」. */
    @Test
    void afterOutbound_writesDebtAndNotice() {
        StockMovement priced = lot(sellerB, 1, "1500", DAY.minusDays(3));
        StockMovement blank = lot(sellerB, 2, null, DAY.minusDays(2));
        StockMovement out1 = out(blank, sellerB, 1);

        Map<Long, List<String>> notices = lotLedgerService.afterOutbound(product.getId(), List.of(out1));

        assertThat(notices.get(out1.getId())).containsExactly("B상사 재고 1개 사용(채무 ₩1,500)");
        out(priced, sellerB, 1);
        StockMovement out3 = out(blank, sellerB, 1);
        Map<Long, List<String>> later = lotLedgerService.afterOutbound(product.getId(), List.of(out3));
        assertThat(later.get(out3.getId())).containsExactly("B상사 재고 1개 사용(채무 미기입)");
        assertThat(sellerDebtRepository.findAll()).extracting(SellerDebt::getAmount)
                .containsExactlyInAnyOrder(new BigDecimal("1500.00"), new BigDecimal("1500.00"), null);
    }

    /** D130 · D131 · D132: an assigned share of an undecided lot belongs to its owner; the other owner's part is a debt. */
    @Test
    void resplit_splitsUndecidedLotOverShares() {
        StockMovement undecided = undecidedLot(4, "1000");
        StockMovement out1 = out(undecided, null, 3);
        share(undecided, sellerA, 1, "1000");
        share(undecided, sellerB, 2, "1200");

        lotLedgerService.resplit(undecided);
        lotLedgerService.rebuildDebts(product.getId());

        assertThat(lotShareUsageRepository.findByOutMovementIdIn(List.of(out1.getId())))
                .extracting(u -> u.getShareRecord() == null ? null : u.getShareRecord().getSeller().getSellerName(),
                        u -> u.getQuantity())
                .containsExactly(tuple("A상사", 1), tuple("B상사", 2));
        assertThat(sellerDebtRepository.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getLender().getId()).isEqualTo(sellerB.getId());
            assertThat(d.getQuantity()).isEqualTo(2);
            assertThat(d.getAmount()).isEqualByComparingTo("2400");
        });
    }

    /** D131 · D116 ①: the undecided part is compared by price with the other owners' shares — dearer goes first. */
    @Test
    void resplit_undecidedPartDearerThanOtherShareGoesFirst() {
        StockMovement undecided = undecidedLot(4, "1000");
        share(undecided, sellerB, 1, "800");
        StockMovement out1 = out(undecided, null, 2);

        lotLedgerService.resplit(undecided);
        lotLedgerService.rebuildDebts(product.getId());

        assertThat(lotShareUsageRepository.findByOutMovementIdIn(List.of(out1.getId())))
                .extracting(u -> u.getShareRecord() == null ? null : u.getShareRecord().getSeller().getSellerName(),
                        u -> u.getQuantity())
                .containsExactly(tuple(null, 2));
        assertThat(sellerDebtRepository.findAll()).isEmpty();
    }

    /** D50 · D116 ③ · D131: a disposal uses its seller's own lots only — never an undecided lot. */
    @Test
    void allocateOwn_skipsUndecidedLots() {
        StockMovement undecided = undecidedLot(3, "1000");
        share(undecided, sellerA, 3, "1000");

        List<LotSlice> slices = lotLedgerService.allocateOwn(product, sellerA, 2);

        assertThat(slices).singleElement().satisfies(s -> {
            assertThat(s.source()).isNull();
            assertThat(s.quantity()).isEqualTo(2);
        });
    }

    /** D139 ② ③: a return goes back into the order seller's own lot; a borrowed piece becomes a new lot at the debt price. */
    @Test
    void allocateReturn_ownBackIntoLotBorrowedAsNewLot() {
        StockMovement own = lot(sellerA, 1, "900", DAY.minusDays(2));
        StockMovement borrowed = lot(sellerB, 1, "2000", DAY.minusDays(3));
        OrderLine line = orderLine();
        StockMovement outOwn = out(own, sellerA, 1, line);
        StockMovement outBorrowed = out(borrowed, sellerB, 1, line);
        lotLedgerService.afterOutbound(product.getId(), List.of(outOwn, outBorrowed));

        List<LotSlice> slices = lotLedgerService.allocateReturn(line, product, sellerA, 2);

        assertThat(slices).hasSize(2);
        assertThat(slices.get(0).source()).isNull();                          // dearest lot first = B's 2,000
        assertThat(slices.get(0).unitPrice()).isEqualByComparingTo("2000");
        assertThat(slices.get(1).source().getId()).isEqualTo(own.getId());
    }

    /** D139 ① ⑦: a piece shipped without a lot comes after every lot piece — a partial return refills the lot first. */
    @Test
    void allocateReturn_noLotPieceComesLast() {
        StockMovement own = lot(sellerA, 1, "900", DAY.minusDays(2));
        OrderLine line = orderLine();
        StockMovement fromLot = out(own, sellerA, 1, line);
        StockMovement noLot = out(null, sellerA, 1, line);
        lotLedgerService.afterOutbound(product.getId(), List.of(fromLot, noLot));

        List<LotSlice> slices = lotLedgerService.allocateReturn(line, product, sellerA, 1);

        assertThat(slices).singleElement().satisfies(s -> assertThat(s.source().getId()).isEqualTo(own.getId()));
    }

    /** D149: a new owner of a lot takes the rows that used it; the debt is rewritten. */
    @Test
    void moveOwner_movesUsersAndRewritesDebt() {
        StockMovement lot = lot(sellerB, 2, "1500", DAY.minusDays(1));
        StockMovement used = out(lot, sellerB, 1);
        lotLedgerService.rebuildDebts(product.getId());
        assertThat(sellerDebtRepository.findAll()).hasSize(1);

        lotLedgerService.moveOwner(List.of(lot), List.of(), sellerA);
        em.flush();
        em.clear();

        assertThat(em.find(StockMovement.class, used.getId()).getSeller().getId()).isEqualTo(sellerA.getId());
        assertThat(sellerDebtRepository.findAll()).isEmpty();
    }

    /** D65: stock link off — one slice, no lot. */
    @Test
    void allocateOutbound_stockLinkOff_noLot() {
        given(purchaseSettingService.isStockLinked()).willReturn(false);
        lot(sellerA, 5, "900", DAY.minusDays(1));

        assertThat(lotLedgerService.allocateOutbound(product, sellerA, 2, DAY)).singleElement()
                .satisfies(s -> assertThat(s.source()).isNull());
    }

    // --- fixtures ---

    private StockMovement lot(Seller owner, int qty, String unitPrice, LocalDate movedOn) {
        return em.persist(StockMovement.builder()
                .product(product).seller(owner).movementType(StockMovementType.STOCK_IN).quantity(qty)
                .location(StockLocation.OWN).reason(StockReason.OPENING)
                .unitPrice(unitPrice == null ? null : new BigDecimal(unitPrice))
                .movedOn(movedOn).createdBy("admin@test.com").build());
    }

    private StockMovement undecidedLot(int qty, String unitPrice) {
        PurchaseRecord record = em.persist(PurchaseRecord.builder()
                .product(product).seller(null).purchasedOn(DAY).quantity(qty)
                .unitPrice(new BigDecimal(unitPrice)).reflectToBasePrice(false).stockLinked(true).build());
        return em.persist(StockMovement.builder()
                .product(product).seller(null).movementType(StockMovementType.STOCK_IN).quantity(qty)
                .location(StockLocation.OWN).reason(StockReason.PURCHASE).unitPrice(new BigDecimal(unitPrice))
                .purchaseRecord(record).movedOn(DAY).createdBy("admin@test.com").build());
    }

    private void share(StockMovement undecided, Seller owner, int qty, String unitPrice) {
        em.persist(PurchaseRecord.builder()
                .product(product).seller(owner).purchasedOn(DAY).quantity(qty)
                .unitPrice(new BigDecimal(unitPrice)).reflectToBasePrice(false).stockLinked(true)
                .sourceMovement(undecided).build());
        em.flush();
    }

    private StockMovement out(StockMovement source, Seller rowSeller, int qty) {
        return out(source, rowSeller, qty, orderLine());
    }

    private StockMovement out(StockMovement source, Seller rowSeller, int qty, OrderLine line) {
        StockMovement saved = em.persist(StockMovement.builder()
                .product(product).seller(rowSeller).movementType(StockMovementType.STOCK_OUT).quantity(-qty)
                .location(StockLocation.OWN).sourceMovement(source).orderLine(line)
                .movedOn(DAY).createdBy("admin@test.com").build());
        em.flush();
        return saved;
    }

    private OrderLine orderLine() {
        Order order = em.persist(Order.builder()
                .marketplaceAccount(accountA).platform(Platform.COUPANG)
                .externalOrderId("O-" + System.nanoTime()).orderedAt(LocalDateTime.of(2026, 11, 2, 10, 0)).build());
        return em.persist(OrderLine.builder()
                .order(order).status(OrderStatus.PAID).itemName("양말세트")
                .orderQty(3).cancelQty(0).holdQty(0).build());
    }
}
