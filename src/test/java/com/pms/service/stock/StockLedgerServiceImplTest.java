package com.pms.service.stock;

import com.pms.domain.OrderClaim;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.StockLocation;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.StockMovementRequest;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.StockMovementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * StockLedgerServiceImpl — the combination rules (prompt 04 Step 4) and the server-owned fields.
 *
 * <p>Everything here is asserted on the {@link StockMovement} handed to {@code save}: sign, unit
 * price, location and createdBy are decided by the server, so the saved row is the only place the
 * decision is visible.
 *
 * <p>Candidate-list semantics (left joins, remaining quantity, correction rows) are NOT here — a
 * mocked repository would assert nothing about the query. They live in
 * {@code repository/StockMovementRepositoryTest} against a real database.
 */
@ExtendWith(MockitoExtension.class)
class StockLedgerServiceImplTest {

    private static final Long PRODUCT_ID = 7L;
    private static final LocalDate MOVED_ON = LocalDate.of(2026, 9, 9);

    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private OrderClaimRepository orderClaimRepository;

    @InjectMocks private StockLedgerServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static final Product PRODUCT = Product.builder().id(PRODUCT_ID).productName("양말A").build();

    /** Stubs the write path: product lookup + save echoing its argument back. */
    private void stubSave() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(PRODUCT));
        given(stockMovementRepository.save(any(StockMovement.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private StockMovement captureSaved() {
        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        return captor.getValue();
    }

    private StockMovementRequest stockIn(StockReason reason, int qty, BigDecimal unitPrice,
                                         Long purchaseRecordId) {
        return new StockMovementRequest(PRODUCT_ID, StockMovementType.STOCK_IN, qty, reason, null,
                unitPrice, null, purchaseRecordId, MOVED_ON);
    }

    @Test
    void testStockInPurchaseInheritsUnitPriceFromPurchaseRecord() {
        stubSave();
        given(purchaseRecordRepository.findById(11L)).willReturn(Optional.of(
                PurchaseRecord.builder().id(11L).quantity(3).unitPrice(new BigDecimal("4000.0000")).build()));

        // The request carries a bogus price on purpose: the purchase ledger is the source of truth.
        service.record(stockIn(StockReason.PURCHASE, 3, new BigDecimal("99999"), 11L));

        StockMovement saved = captureSaved();
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("4000.0000");
        assertThat(saved.getPurchaseRecord().getId()).isEqualTo(11L);
    }

    @Test
    void testStockInPurchaseWithUnknownAmountSavesNullUnitPrice() {
        stubSave();
        given(purchaseRecordRepository.findById(11L)).willReturn(Optional.of(
                PurchaseRecord.builder().id(11L).quantity(3).unitPrice(null).build()));

        service.record(stockIn(StockReason.PURCHASE, 3, null, 11L));

        // null means "amount unknown"; zero would claim the goods were free.
        assertThat(captureSaved().getUnitPrice()).isNull();
    }

    @Test
    void testStockInOpeningRequiresUnitPrice() {
        assertThatThrownBy(() -> service.record(stockIn(StockReason.OPENING, 5, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void testStockInFreeForcesZeroUnitPrice() {
        stubSave();

        service.record(stockIn(StockReason.FREE, 2, new BigDecimal("500"), null));

        assertThat(captureSaved().getUnitPrice()).isEqualByComparingTo("0");
    }

    @Test
    void testDisposalStoresNegativeQuantity() {
        stubSave();

        service.record(new StockMovementRequest(PRODUCT_ID, StockMovementType.DISPOSAL, 3,
                StockReason.DAMAGED, null, null, null, null, MOVED_ON));

        // Entered as "3 discarded" — the client never sends the sign.
        assertThat(captureSaved().getQuantity()).isEqualTo(-3);
    }

    @Test
    void testAdjustAcceptsNegativeQuantity() {
        stubSave();

        service.record(new StockMovementRequest(PRODUCT_ID, StockMovementType.ADJUST, -2,
                StockReason.COUNT_DIFF, null, null, null, null, MOVED_ON));

        assertThat(captureSaved().getQuantity()).isEqualTo(-2);
    }

    @Test
    void testAdjustRejectsZeroQuantity() {
        assertThatThrownBy(() -> service.record(new StockMovementRequest(PRODUCT_ID,
                StockMovementType.ADJUST, 0, StockReason.COUNT_DIFF, null, null, null, null, MOVED_ON)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void testReasonNotBelongingToTypeRejected() {
        assertThatThrownBy(() -> service.record(stockIn(StockReason.DAMAGED, 1, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void testEtcReasonRequiresNote() {
        assertThatThrownBy(() -> service.record(new StockMovementRequest(PRODUCT_ID,
                StockMovementType.DISPOSAL, 1, StockReason.ETC, "   ", null, null, null, MOVED_ON)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void testReturnInRequiresClaimId() {
        assertThatThrownBy(() -> service.record(new StockMovementRequest(PRODUCT_ID,
                StockMovementType.RETURN_IN, 1, null, null, null, null, null, MOVED_ON)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void testReturnInStoresClaimAndPositiveQuantity() {
        stubSave();
        given(orderClaimRepository.findById(21L))
                .willReturn(Optional.of(OrderClaim.builder().id(21L).quantity(2).build()));

        service.record(new StockMovementRequest(PRODUCT_ID, StockMovementType.RETURN_IN, 2,
                null, null, null, 21L, null, MOVED_ON));

        StockMovement saved = captureSaved();
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getOrderClaim().getId()).isEqualTo(21L);
        assertThat(saved.getReason()).isNull();
    }

    /**
     * Inbound rows are never tied to a single order (D6) — one delivery is consumed by many orders.
     * The request cannot even carry an {@code orderLineId} (that field belongs to STOCK_OUT), so the
     * claim reference is what has to be rejected here.
     */
    @Test
    void testStockInRejectsOrderLineReference() {
        assertThatThrownBy(() -> service.record(new StockMovementRequest(PRODUCT_ID,
                StockMovementType.STOCK_IN, 1, StockReason.FREE, null, null, 21L, null, MOVED_ON)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void testStockOutRejectedOnThisEndpoint() {
        assertThatThrownBy(() -> service.record(new StockMovementRequest(PRODUCT_ID,
                StockMovementType.STOCK_OUT, 1, null, null, null, null, null, MOVED_ON)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void testCreatedByFilledFromSecurityContext() {
        stubSave();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test.com", null, List.of()));

        service.record(stockIn(StockReason.FREE, 1, null, null));

        assertThat(captureSaved().getCreatedBy()).isEqualTo("admin@test.com");
    }

    @Test
    void testLocationAlwaysOwn() {
        stubSave();

        service.record(stockIn(StockReason.FREE, 1, null, null));

        assertThat(captureSaved().getLocation()).isEqualTo(StockLocation.OWN);
    }
}
