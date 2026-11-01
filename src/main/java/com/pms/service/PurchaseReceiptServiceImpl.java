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
import com.pms.dto.response.ReceiptCreateResult;
import com.pms.dto.response.ReceiptDetailView;
import com.pms.dto.response.ReceiptLineView;
import com.pms.dto.response.ReceiptSummaryView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.PurchaseReceiptRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.cost.CostPropagationService;
import com.pms.service.stock.LotLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final ProductRepository productRepository;
    private final CostPropagationService costPropagationService;
    private final LotLedgerService lotLedgerService;

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
        // FEATURE_2610_19 D149 · D132: the rows that used those lots follow too, assigned shares (D130) re-split their
        // undecided lots, and the debts of the touched products are rewritten.
        if (payerChanged && !lines.isEmpty()) {
            List<StockMovement> movements = stockMovementRepository
                    .findByPurchaseRecordIdIn(lines.stream().map(PurchaseRecord::getId).toList());
            List<PurchaseRecord> shares = lines.stream().filter(r -> r.getSourceMovement() != null).toList();
            lotLedgerService.moveOwner(movements, shares, payer);
        }
        return get(receiptId);
    }

    /**
     * Receipt assignment from the daily in/out view (FEATURE_2610_19 D118 · D121 · D130 · D135).
     *
     * <p>Per product: the quantity is taken from that day's undecided intake rows, lowest record order first (D130);
     * each taken part becomes one purchase record of the payer seller pointing at its intake row, and the intake row's
     * undecided record gets the same quantity as a minus row. No stock row is written (D125).
     *
     * <p>Amount: a total is spread over the parts with one unit price (total ÷ quantity, 4 decimals) and the last part
     * takes the rounding remainder, so the parts add up to the total typed. Neither typed → the registered price,
     * base price not moved (D135); no registered price → empty.
     */
    @Override
    @Transactional
    public ReceiptCreateResult assign(LocalDate movedOn, ReceiptCreateRequest request, MultipartFile photo) {
        if (request == null || movedOn == null) {
            throw new IllegalArgumentException("영수증 내용을 보내세요.");
        }
        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("영수증 사진을 올리세요.");
        }
        Map<Long, Long> perProduct = request.lines().stream()
                .collect(Collectors.groupingBy(ReceiptLineRequest::productId, Collectors.counting()));
        if (perProduct.values().stream().anyMatch(count -> count > 1)) {
            throw new IllegalArgumentException("같은 상품을 두 번 넣었습니다.");
        }
        SellerCard card = request.cardId() == null ? null : sellerCardService.requireVisible(request.cardId());
        Seller payer = card != null ? card.getSeller() : requirePayer(request.payerSellerId());

        // D121 ①: never more than what is still undecided that day.
        Map<Long, List<StockMovement>> intakeByProduct = new LinkedHashMap<>();
        for (ReceiptLineRequest line : request.lines()) {
            List<StockMovement> intake = stockMovementRepository.findUndecidedIntake(line.productId(), movedOn);
            int left = intake.stream().mapToInt(this::undecidedLeft).sum();
            if (line.quantity() > left) {
                throw new IllegalArgumentException("남은 영수증 미등록 수량(" + left + "개)보다 많습니다.");
            }
            intakeByProduct.put(line.productId(), intake);
        }
        imageValidator.validate(photo);
        PurchaseReceipt receipt = purchaseReceiptRepository.save(PurchaseReceipt.builder()
                .purchasedOn(request.purchasedOn())
                .payerSeller(payer)
                .card(card)
                .photoUrl(storePhoto(photo))
                .createdBy(currentUsername())
                .build());

        List<StockMovement> touched = new ArrayList<>();
        for (ReceiptLineRequest line : request.lines()) {
            Product product = productRepository.findById(line.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", line.productId()));
            // D130: take the quantity from that day's intake rows, lowest record order first.
            List<Map.Entry<StockMovement, Integer>> parts = new ArrayList<>();
            int left = line.quantity();
            for (StockMovement intake : intakeByProduct.get(line.productId())) {
                int part = Math.min(left, undecidedLeft(intake));
                if (part <= 0) continue;
                parts.add(Map.entry(intake, part));
                left -= part;
                if (left == 0) break;
            }
            // D135 ① round 32: the amounts are computed by PurchaseRecord's factories only.
            List<PurchaseRecord> assigned = PurchaseRecord.assignedParts(product, payer, request.purchasedOn(),
                    line.totalAmount(), line.unitPrice(), line.reflectToBasePrice(), receipt, parts);
            for (int index = 0; index < parts.size(); index++) {
                costPropagationService.updateBasePrice(purchaseRecordRepository.save(assigned.get(index)));
                StockMovement intake = parts.get(index).getKey();
                // D130 ② · D135 ③: the undecided record loses the same quantity at its own unit price.
                purchaseRecordRepository.save(
                        PurchaseRecord.assignmentMinus(intake.getPurchaseRecord(), parts.get(index).getValue()));
                touched.add(intake);
            }
        }
        purchaseRecordRepository.flush();
        // D130 · D131 · D132: the owners of those intake rows changed — re-split them and rewrite the debts.
        for (StockMovement intake : touched) {
            lotLedgerService.resplit(intake);
        }
        touched.stream().map(m -> m.getProduct().getId()).distinct().forEach(lotLedgerService::rebuildDebts);
        return new ReceiptCreateResult(receipt.getId());
    }

    /** What of one undecided intake row no receipt took yet (D121 ①): its record minus the assigned records. */
    private int undecidedLeft(StockMovement intake) {
        int assigned = purchaseRecordRepository.sumAssignedFrom(intake.getId());
        return intake.getQuantity() - assigned;
    }

    private Seller requirePayer(Long payerSellerId) {
        if (payerSellerId == null) {
            throw new IllegalArgumentException("결제 판매자를 선택하세요.");
        }
        return sellerRepository.findById(payerSellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller", payerSellerId));
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
