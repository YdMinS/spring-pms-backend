package com.pms.service;

import com.pms.domain.PurchaseReceipt;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.SellerCard;
import com.pms.domain.StockMovement;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.request.ReceiptCreateRequest;
import com.pms.dto.request.ReceiptLineRequest;
import com.pms.dto.request.ReceiptUpdateRequest;
import com.pms.dto.response.ReceiptCreateResult;
import com.pms.dto.response.ReceiptDetailView;
import com.pms.dto.response.ReceiptLineView;
import com.pms.dto.response.ReceiptSummaryView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.PurchaseReceiptRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link PurchaseReceiptService} implementation.
 *
 * Class default readOnly, write methods override with @Transactional.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PurchaseReceiptServiceImpl implements PurchaseReceiptService {

    /** Storage category of receipt photos ({@code ImageStorageService.uploadBytes}). */
    static final String PHOTO_CATEGORY = "receipts";

    private final PurchaseReceiptRepository purchaseReceiptRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final SellerRepository sellerRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SellerCardService sellerCardService;
    private final PurchaseListService purchaseListService;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;

    @Override
    @Transactional
    public ReceiptCreateResult create(ReceiptCreateRequest request, MultipartFile photo) {
        if (request == null) {
            throw new IllegalArgumentException("영수증 내용을 보내세요.");
        }
        // D76: a receipt without a photo is not saved here — the per-product check-in is the way in (D75).
        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("영수증 사진을 올리세요.");
        }
        // D87: a registered card decides the payer seller; without one the person picks the payer seller.
        SellerCard card = null;
        Seller payer;
        if (request.cardId() != null) {
            card = sellerCardService.requireVisible(request.cardId());
            payer = card.getSeller();
        } else {
            if (request.payerSellerId() == null) {
                throw new IllegalArgumentException("결제 판매자를 선택하세요.");
            }
            payer = sellerRepository.findById(request.payerSellerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Seller", request.payerSellerId()));
        }
        imageValidator.validate(photo);
        String photoUrl = storePhoto(photo);

        PurchaseReceipt receipt = purchaseReceiptRepository.save(PurchaseReceipt.builder()
                .purchasedOn(request.purchasedOn())
                .payerSeller(payer)
                .card(card)
                .photoUrl(photoUrl)
                .createdBy(currentUsername())
                .build());

        // D89: every line belongs to the payer seller. D88: no debt is written here.
        for (ReceiptLineRequest line : request.lines()) {
            purchaseListService.addPurchase(new PurchaseRecordRequest(
                    line.productId(),             // productId
                    payer.getId(),                // sellerId
                    request.purchasedOn(),        // purchasedOn
                    line.quantity(),              // quantity
                    line.totalAmount(),           // totalAmount
                    line.unitPrice(),             // unitPrice
                    line.reflectToBasePrice(),    // reflectToBasePrice
                    Boolean.TRUE), receipt);      // recordStock — the stock link setting decides (FEATURE_2610_09 D65)
        }
        return new ReceiptCreateResult(receipt.getId());
    }

    @Override
    @Transactional
    public ReceiptDetailView update(Long receiptId, ReceiptUpdateRequest request) {
        PurchaseReceipt receipt = purchaseReceiptRepository.findDetailById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseReceipt", receiptId));
        // D100 ②: the same card rule as create. D100 ⑥: the receipt's own card may stay even when it is hidden.
        SellerCard card = null;
        Seller payer;
        if (request.cardId() != null) {
            card = (receipt.getCard() != null && receipt.getCard().getId().equals(request.cardId()))
                    ? receipt.getCard()
                    : sellerCardService.requireVisible(request.cardId());
            payer = card.getSeller();
        } else {
            if (request.payerSellerId() == null) {
                throw new IllegalArgumentException("결제 판매자를 선택하세요.");
            }
            payer = sellerRepository.findById(request.payerSellerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Seller", request.payerSellerId()));
        }
        boolean payerChanged = !receipt.getPayerSeller().getId().equals(payer.getId());

        purchaseReceiptRepository.save(receipt.toBuilder()
                .purchasedOn(request.purchasedOn())
                .payerSeller(payer)
                .card(card)
                .build());

        // D100 ③: every line follows the header (seller and purchase day). Amounts, quantities, the base price and
        // the purchase checks stay (D100 ④ ⑤).
        List<PurchaseRecord> lines = purchaseRecordRepository.findByReceiptIds(List.of(receiptId));
        purchaseRecordRepository.saveAll(lines.stream()
                .map(r -> r.toBuilder().seller(payer).purchasedOn(request.purchasedOn()).build())
                .toList());

        // D101: a new payer seller takes the stock rows of those lines; their day, quantity and price stay.
        // A purchase-day-only edit leaves stock alone.
        if (payerChanged && !lines.isEmpty()) {
            List<StockMovement> movements = stockMovementRepository
                    .findByPurchaseRecordIdIn(lines.stream().map(PurchaseRecord::getId).toList());
            stockMovementRepository.saveAll(movements.stream()
                    .map(m -> m.toBuilder().seller(payer).build())
                    .toList());
        }
        return get(receiptId);
    }

    @Override
    public List<ReceiptSummaryView> search(LocalDate from, LocalDate to, Long sellerId, String productName) {
        String name = (productName == null || productName.isBlank()) ? null : productName.trim();
        List<PurchaseReceipt> receipts = purchaseReceiptRepository.search(from, to, sellerId, name);
        if (receipts.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PurchaseRecord>> linesByReceipt = purchaseRecordRepository
                .findByReceiptIds(receipts.stream().map(PurchaseReceipt::getId).toList()).stream()
                .collect(Collectors.groupingBy(r -> r.getReceipt().getId()));
        return receipts.stream()
                .map(receipt -> toSummary(receipt, linesByReceipt.getOrDefault(receipt.getId(), List.of())))
                .toList();
    }

    @Override
    public ReceiptDetailView get(Long receiptId) {
        PurchaseReceipt receipt = purchaseReceiptRepository.findDetailById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseReceipt", receiptId));
        List<ReceiptLineView> lines = purchaseRecordRepository.findByReceiptIds(List.of(receiptId)).stream()
                .map(r -> new ReceiptLineView(r.getProduct().getProductName(), r.getQuantity(),
                        r.getTotalAmount(), r.getUnitPrice(), Boolean.TRUE.equals(r.getReflectToBasePrice())))
                .toList();
        SellerCard card = receipt.getCard();
        return new ReceiptDetailView(receipt.getId(), receipt.getPurchasedOn(), receipt.getPayerSeller().getId(),
                receipt.getPayerSeller().getSellerName(), card == null ? null : card.getId(), cardAlias(receipt),
                card == null ? null : card.getMaskedNumber(), receipt.getPhotoUrl(), lines);
    }

    // --- helpers ---

    /** D94: first product, line count, sum of the lines with an amount (null when every line is blank). */
    private ReceiptSummaryView toSummary(PurchaseReceipt receipt, List<PurchaseRecord> lines) {
        List<BigDecimal> amounts = lines.stream().map(PurchaseRecord::getTotalAmount).filter(Objects::nonNull).toList();
        BigDecimal total = amounts.isEmpty() ? null : amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        String firstProductName = lines.isEmpty() ? null : lines.get(0).getProduct().getProductName();
        return new ReceiptSummaryView(receipt.getId(), receipt.getPurchasedOn(), receipt.getPayerSeller().getId(),
                receipt.getPayerSeller().getSellerName(), cardAlias(receipt), firstProductName, lines.size(), total);
    }

    private String cardAlias(PurchaseReceipt receipt) {
        return receipt.getCard() == null ? null : receipt.getCard().getAlias();
    }

    /** Same storage path as the thumbnail assets ({@code TemplateAssetServiceImpl.upload}). */
    private String storePhoto(MultipartFile photo) {
        String original = photo.getOriginalFilename() == null ? "" : photo.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String extension = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        String contentType = photo.getContentType() != null ? photo.getContentType() : "image/" + extension;
        String filename = "receipt_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8) + "." + extension;
        byte[] bytes;
        try {
            bytes = photo.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("영수증 사진을 읽지 못했습니다.", e);
        }
        return imageStorageService.uploadBytes(bytes, PHOTO_CATEGORY, filename, contentType);
    }

    /** Same helper as {@code PurchaseListServiceImpl.currentUsername()}. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
