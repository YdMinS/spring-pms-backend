package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.PurchaseReceipt;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.SellerCard;
import com.pms.domain.StockMovement;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.request.ReceiptCreateRequest;
import com.pms.dto.request.ReceiptLineRequest;
import com.pms.dto.request.ReceiptUpdateRequest;
import com.pms.dto.response.PurchaseRecordResult;
import com.pms.dto.response.ReceiptSummaryView;
import com.pms.repository.PurchaseReceiptRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PurchaseReceiptServiceImpl — payer seller from the card or picked by hand (D87), photo required (D76), every line
 * owned by the payer seller through the check-in (D89), list summary sums (D94), header edit moves the lines and their
 * stock rows (D100 · D101).
 */
@ExtendWith(MockitoExtension.class)
class PurchaseReceiptServiceImplTest {

    @Mock private PurchaseReceiptRepository purchaseReceiptRepository;
    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private SellerCardService sellerCardService;
    @Mock private PurchaseListService purchaseListService;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private PurchaseReceiptServiceImpl service;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final Seller SELLER_A = Seller.builder().id(3L).sellerName("A상사").build();
    private static final Seller SELLER_B = Seller.builder().id(9L).sellerName("B상사").build();

    private final MockMultipartFile photo =
            new MockMultipartFile("photo", "receipt.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    private ReceiptCreateRequest request(Long cardId, Long payerSellerId) {
        return new ReceiptCreateRequest(DAY, cardId, payerSellerId, List.of(
                new ReceiptLineRequest(100L, 2, new BigDecimal("6000"), null, null),
                new ReceiptLineRequest(101L, 1, null, null, null)));
    }

    private void stubSave() {
        given(imageStorageService.uploadBytes(any(), eq("receipts"), anyString(), eq("image/jpeg")))
                .willReturn("https://bucket/receipts/r.jpg");
        given(purchaseReceiptRepository.save(any(PurchaseReceipt.class)))
                .willAnswer(inv -> inv.<PurchaseReceipt>getArgument(0).toBuilder().id(50L).build());
        given(purchaseListService.addPurchase(any(PurchaseRecordRequest.class), any(PurchaseReceipt.class)))
                .willReturn(new PurchaseRecordResult(1L, false));
    }

    /** D87 · D89: a card → its owner pays and owns every line; payerSellerId is ignored. */
    @Test
    void testCreateWithCardUsesCardOwnerForEveryLine() {
        given(sellerCardService.requireVisible(7L))
                .willReturn(SellerCard.builder().id(7L).seller(SELLER_B).alias("B법인").maskedNumber("1234-****").build());
        stubSave();

        service.create(request(7L, 3L), photo);

        ArgumentCaptor<PurchaseReceipt> receipt = ArgumentCaptor.forClass(PurchaseReceipt.class);
        verify(purchaseReceiptRepository).save(receipt.capture());
        assertThat(receipt.getValue().getPayerSeller()).isSameAs(SELLER_B);
        assertThat(receipt.getValue().getPhotoUrl()).isEqualTo("https://bucket/receipts/r.jpg");
        ArgumentCaptor<PurchaseRecordRequest> lines = ArgumentCaptor.forClass(PurchaseRecordRequest.class);
        verify(purchaseListService, times(2)).addPurchase(lines.capture(), any(PurchaseReceipt.class));
        assertThat(lines.getAllValues()).extracting(PurchaseRecordRequest::sellerId).containsExactly(9L, 9L);
        assertThat(lines.getAllValues()).extracting(PurchaseRecordRequest::purchasedOn).containsExactly(DAY, DAY);
        verify(sellerRepository, never()).findById(any());
    }

    /** D87: no card → the picked payer seller; the receipt keeps card null. */
    @Test
    void testCreateWithoutCardUsesPickedSeller() {
        given(sellerRepository.findById(3L)).willReturn(Optional.of(SELLER_A));
        stubSave();

        service.create(request(null, 3L), photo);

        ArgumentCaptor<PurchaseReceipt> receipt = ArgumentCaptor.forClass(PurchaseReceipt.class);
        verify(purchaseReceiptRepository).save(receipt.capture());
        assertThat(receipt.getValue().getPayerSeller()).isSameAs(SELLER_A);
        assertThat(receipt.getValue().getCard()).isNull();
    }

    /** D87: no card and no payer seller → 400, nothing stored. */
    @Test
    void testCreateWithoutCardOrSellerIsRejected() {
        assertThatThrownBy(() -> service.create(request(null, null), photo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 판매자를 선택하세요.");
        verify(imageStorageService, never()).uploadBytes(any(), anyString(), anyString(), anyString());
    }

    /** D76: no photo → 400, nothing stored. */
    @Test
    void testCreateWithoutPhotoIsRejected() {
        assertThatThrownBy(() -> service.create(request(null, 3L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("영수증 사진을 올리세요.");
        verify(purchaseReceiptRepository, never()).save(any());
    }

    /** D94: first product, line count, sum of the lines with an amount; all blank → null. */
    @Test
    void testSearchSummarizesLines() {
        PurchaseReceipt r1 = PurchaseReceipt.builder().id(50L).purchasedOn(DAY).payerSeller(SELLER_A).build();
        PurchaseReceipt r2 = PurchaseReceipt.builder().id(51L).purchasedOn(DAY).payerSeller(SELLER_A).build();
        given(purchaseReceiptRepository.search(DAY.minusDays(29), DAY, null, null)).willReturn(List.of(r1, r2));
        Product a = Product.builder().id(100L).productName("양말A").build();
        Product b = Product.builder().id(101L).productName("양말B").build();
        given(purchaseRecordRepository.findByReceiptIds(List.of(50L, 51L))).willReturn(List.of(
                PurchaseRecord.builder().id(1L).receipt(r1).product(a).quantity(2).totalAmount(new BigDecimal("6000.00")).build(),
                PurchaseRecord.builder().id(2L).receipt(r1).product(b).quantity(1).build(),
                PurchaseRecord.builder().id(3L).receipt(r2).product(b).quantity(1).build()));

        List<ReceiptSummaryView> rows = service.search(DAY.minusDays(29), DAY, null, " ");

        assertThat(rows).extracting(ReceiptSummaryView::firstProductName).containsExactly("양말A", "양말B");
        assertThat(rows).extracting(ReceiptSummaryView::productCount).containsExactly(2, 1);
        assertThat(rows.get(0).totalAmount()).isEqualByComparingTo("6000");
        assertThat(rows.get(1).totalAmount()).isNull();
        assertThat(rows.get(0).cardAlias()).isNull();
    }

    // --- header edit (D100 · D101) ---

    private static final LocalDate NEW_DAY = LocalDate.of(2026, 10, 17);

    private PurchaseRecord line(PurchaseReceipt receipt, Seller seller) {
        return PurchaseRecord.builder().id(1L).receipt(receipt).seller(seller).purchasedOn(DAY).quantity(2)
                .product(Product.builder().id(100L).productName("양말A").build()).build();
    }

    /** D100 · D101: a new card → its owner pays; every line and its stock row move to that seller, stock day kept. */
    @Test
    void testUpdateMovesLinesAndStockToCardOwner() {
        PurchaseReceipt receipt = PurchaseReceipt.builder().id(50L).purchasedOn(DAY).payerSeller(SELLER_A).build();
        given(purchaseReceiptRepository.findDetailById(50L)).willReturn(Optional.of(receipt));
        SellerCard cardB = SellerCard.builder().id(7L).seller(SELLER_B).alias("B법인").maskedNumber("1234-****").build();
        given(sellerCardService.requireVisible(7L)).willReturn(cardB);
        given(purchaseRecordRepository.findByReceiptIds(List.of(50L))).willReturn(List.of(line(receipt, SELLER_A)));
        given(stockMovementRepository.findByPurchaseRecordIdIn(List.of(1L))).willReturn(List.of(
                StockMovement.builder().id(30L).seller(SELLER_A).quantity(2).movedOn(DAY).build()));

        service.update(50L, new ReceiptUpdateRequest(NEW_DAY, 7L, 3L));

        ArgumentCaptor<PurchaseReceipt> saved = ArgumentCaptor.forClass(PurchaseReceipt.class);
        verify(purchaseReceiptRepository).save(saved.capture());
        assertThat(saved.getValue().getPayerSeller()).isSameAs(SELLER_B);
        assertThat(saved.getValue().getCard()).isSameAs(cardB);
        assertThat(saved.getValue().getPurchasedOn()).isEqualTo(NEW_DAY);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PurchaseRecord>> lines = ArgumentCaptor.forClass(List.class);
        verify(purchaseRecordRepository).saveAll(lines.capture());
        assertThat(lines.getValue()).singleElement().satisfies(r -> {
            assertThat(r.getSeller()).isSameAs(SELLER_B);
            assertThat(r.getPurchasedOn()).isEqualTo(NEW_DAY);
            assertThat(r.getQuantity()).isEqualTo(2);
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockMovement>> movements = ArgumentCaptor.forClass(List.class);
        verify(stockMovementRepository).saveAll(movements.capture());
        assertThat(movements.getValue()).singleElement().satisfies(m -> {
            assertThat(m.getSeller()).isSameAs(SELLER_B);
            assertThat(m.getMovedOn()).isEqualTo(DAY);
            assertThat(m.getQuantity()).isEqualTo(2);
        });
    }

    /** D100 ⑥ · D101: the receipt's own hidden card may stay; a day-only edit leaves stock alone. */
    @Test
    void testUpdateKeepsHiddenCardAndLeavesStockOnDayOnlyEdit() {
        SellerCard hidden = SellerCard.builder().id(7L).seller(SELLER_A).alias("A법인").maskedNumber("1234-****")
                .hiddenAt(LocalDateTime.of(2026, 10, 1, 9, 0)).build();
        PurchaseReceipt receipt = PurchaseReceipt.builder().id(50L).purchasedOn(DAY).payerSeller(SELLER_A).card(hidden)
                .build();
        given(purchaseReceiptRepository.findDetailById(50L)).willReturn(Optional.of(receipt));
        given(purchaseRecordRepository.findByReceiptIds(List.of(50L))).willReturn(List.of(line(receipt, SELLER_A)));

        service.update(50L, new ReceiptUpdateRequest(NEW_DAY, 7L, null));

        verify(sellerCardService, never()).requireVisible(any());
        verify(stockMovementRepository, never()).findByPurchaseRecordIdIn(any());
        ArgumentCaptor<PurchaseReceipt> saved = ArgumentCaptor.forClass(PurchaseReceipt.class);
        verify(purchaseReceiptRepository).save(saved.capture());
        assertThat(saved.getValue().getCard()).isSameAs(hidden);
        assertThat(saved.getValue().getPurchasedOn()).isEqualTo(NEW_DAY);
    }
}
