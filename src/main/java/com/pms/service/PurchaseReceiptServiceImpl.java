package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.PurchaseReceipt;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.SellerCard;
import com.pms.domain.StockMovement;
import com.pms.dto.request.DailyRowEditRequest;
import com.pms.dto.request.PurchasePriceRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.request.ReceiptCreateRequest;
import com.pms.dto.request.ReceiptLineRequest;
import com.pms.dto.request.ReceiptUpdateRequest;
import com.pms.dto.request.ReceiptlessAssignRequest;
import com.pms.dto.request.SellerChangeRequest;
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
import com.pms.service.stock.BasePriceFillService;
import com.pms.service.stock.LotLedgerService;
import com.pms.service.stock.StockDateEditService;
import com.pms.service.stock.StockFillService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final BasePriceFillService basePriceFillService;
    private final StockDateEditService stockDateEditService;
    private final StockFillService stockFillService;

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
        List<Long> lotIds = new ArrayList<>();
        for (ReceiptLineRequest line : request.lines()) {
            Long lotId = purchaseListService.addPurchase(new PurchaseRecordRequest(
                    line.productId(),             // productId
                    payer.getId(),                // sellerId
                    request.purchasedOn(),        // purchasedOn
                    line.quantity(),              // quantity
                    line.totalAmount(),           // totalAmount
                    line.unitPrice(),             // unitPrice
                    line.reflectToBasePrice(),    // reflectToBasePrice
                    Boolean.TRUE), receipt).lotId(); // recordStock — the stock link setting decides (FEATURE_2610_09 D65)
            if (lotId != null) {
                lotIds.add(lotId);                // FEATURE_2611_22 D3 ①: the 「채울까요?」 window asks for these
            }
        }
        return new ReceiptCreateResult(receipt.getId(), lotIds);
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
        List<PurchaseRecord> lines = purchaseRecordRepository.findByReceiptIds(List.of(receiptId));
        // FEATURE_2611_21 D26 · D31 · D32: a new purchase day also moves the stock rows of the lines (checked before
        // anything is written) and re-decides the base price (D14 · D28); a share keeps its undecided row (D30).
        if (!request.purchasedOn().equals(receipt.getPurchasedOn())) {
            stockDateEditService.moveReceiptDay(lines, request.purchasedOn());
        }

        purchaseReceiptRepository.save(receipt.toBuilder()
                .purchasedOn(request.purchasedOn())
                .payerSeller(payer)
                .card(card)
                .build());

        // D100 ③: every line follows the header (seller and purchase day). Amounts, quantities and the purchase checks
        // stay (D100 ④).
        purchaseRecordRepository.saveAll(lines.stream()
                .map(r -> r.toBuilder().seller(payer).purchasedOn(request.purchasedOn()).build())
                .toList());

        // D101: a new payer seller takes the stock rows of those lines; their quantity and price stay.
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
        SellerCard card = request.cardId() == null ? null : sellerCardService.requireVisible(request.cardId());
        Seller payer = card != null ? card.getSeller() : requirePayer(request.payerSellerId());
        Map<Long, List<StockMovement>> intakeByProduct = undecidedIntake(movedOn, request.lines());
        imageValidator.validate(photo);
        PurchaseReceipt receipt = purchaseReceiptRepository.save(PurchaseReceipt.builder()
                .purchasedOn(request.purchasedOn())
                .payerSeller(payer)
                .card(card)
                .photoUrl(storePhoto(photo))
                .createdBy(currentUsername())
                .build());
        writeAssignment(intakeByProduct, request.lines(), payer, request.purchasedOn(), receipt, null);
        return new ReceiptCreateResult(receipt.getId());
    }

    /**
     * 「영수증 없이 판매자 정하기」 (FEATURE_2611_01 D2 · D4 · D5 · D8 · D12): the same assignment as {@link #assign} with
     * the picked seller and the screen day as the purchase day. 「영수증 나중에」 = a receipt without a photo (payer = that
     * seller, no card) · 「영수증 없음」 = no receipt, every record marked as found stock.
     */
    @Override
    @Transactional
    public ReceiptCreateResult assignWithoutReceipt(LocalDate movedOn, ReceiptlessAssignRequest request) {
        if (request == null || movedOn == null) {
            throw new IllegalArgumentException("영수증 내용을 보내세요.");
        }
        Seller seller = requireSeller(request.sellerId());
        Map<Long, List<StockMovement>> intakeByProduct = undecidedIntake(movedOn, request.lines());
        PurchaseReceipt receipt = null;
        LocalDateTime foundStockAt = null;
        if (request.mode() == ReceiptlessAssignRequest.Mode.RECEIPT_LATER) {
            receipt = purchaseReceiptRepository.save(PurchaseReceipt.builder()
                    .purchasedOn(movedOn)
                    .payerSeller(seller)
                    .card(null)
                    .photoUrl(null)
                    .createdBy(currentUsername())
                    .build());
        } else {
            foundStockAt = LocalDateTime.now();
        }
        writeAssignment(intakeByProduct, request.lines(), seller, movedOn, receipt, foundStockAt);
        return new ReceiptCreateResult(receipt == null ? null : receipt.getId());
    }

    /**
     * Photo of a 「영수증 나중에」 receipt (FEATURE_2611_01 D4): stored once. A receipt that already has its photo → 400
     * (FEATURE_2610_18 D100 — a photo is not replaced).
     */
    @Override
    @Transactional
    public ReceiptDetailView attachPhoto(Long receiptId, MultipartFile photo) {
        PurchaseReceipt receipt = purchaseReceiptRepository.findDetailById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseReceipt", receiptId));
        if (receipt.getPhotoUrl() != null) {
            throw new IllegalArgumentException("이미 사진이 있는 영수증입니다.");
        }
        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("영수증 사진을 올리세요.");
        }
        imageValidator.validate(photo);
        purchaseReceiptRepository.save(receipt.toBuilder().photoUrl(storePhoto(photo)).build());
        return get(receiptId);
    }

    /**
     * 「판매자 바꾸기」 (FEATURE_2611_01 D7 · D15): the rows and shares take the new seller — a row's purchase record and
     * every stock row of that record too — then {@link LotLedgerService#moveOwner} moves the rows that used them,
     * re-splits the shares' lots and rewrites the debts (D6 · FEATURE_2610_19 D132 · D149).
     */
    @Override
    @Transactional
    public void changeSeller(SellerChangeRequest request) {
        Seller seller = requireSeller(request.sellerId());
        List<Long> movementIds = request.movementIds() == null ? List.of() : request.movementIds();
        List<Long> shareIds = request.shareRecordIds() == null ? List.of() : request.shareRecordIds();
        if (movementIds.isEmpty() && shareIds.isEmpty()) {
            throw new IllegalArgumentException("판매자를 바꿀 줄을 고르세요.");
        }
        Map<Long, StockMovement> foundRows = movementIds.isEmpty() ? Map.of()
                : stockMovementRepository.findByIdIn(movementIds).stream()
                .collect(Collectors.toMap(StockMovement::getId, m -> m));
        List<StockMovement> rows = new ArrayList<>();
        for (Long id : movementIds) {
            StockMovement row = foundRows.get(id);
            if (row == null) {
                throw new ResourceNotFoundException("StockMovement", id);
            }
            if (!row.isSellerChangeableWithoutReceipt()) {
                throw new IllegalArgumentException("판매자를 바꿀 수 없는 줄입니다.");
            }
            rows.add(row);
        }
        Map<Long, PurchaseRecord> foundShares = shareIds.isEmpty() ? Map.of()
                : purchaseRecordRepository.findAllById(shareIds).stream()
                .collect(Collectors.toMap(PurchaseRecord::getId, r -> r));
        List<PurchaseRecord> shares = new ArrayList<>();
        for (Long id : shareIds) {
            PurchaseRecord share = foundShares.get(id);
            if (share == null) {
                throw new ResourceNotFoundException("PurchaseRecord", id);
            }
            if (!share.isNoReceiptShare()) {
                throw new IllegalArgumentException("판매자를 바꿀 수 없는 줄입니다.");
            }
            shares.add(share);
        }
        // D7: the purchase record of a row moves with it, and so does every other stock row of that record (D101).
        Map<Long, PurchaseRecord> rowRecords = new LinkedHashMap<>();
        rows.stream().map(StockMovement::getPurchaseRecord).filter(Objects::nonNull)
                .forEach(r -> rowRecords.put(r.getId(), r));
        Map<Long, StockMovement> lots = new LinkedHashMap<>();
        rows.forEach(row -> lots.put(row.getId(), row));
        if (!rowRecords.isEmpty()) {
            stockMovementRepository.findByPurchaseRecordIdIn(List.copyOf(rowRecords.keySet()))
                    .forEach(row -> lots.putIfAbsent(row.getId(), row));
        }
        List<PurchaseRecord> movedRecords = new ArrayList<>();
        rowRecords.values().forEach(r -> movedRecords.add(r.toBuilder().seller(seller).build()));
        List<PurchaseRecord> movedShares = shares.stream().map(s -> s.toBuilder().seller(seller).build()).toList();
        movedRecords.addAll(movedShares);
        purchaseRecordRepository.saveAll(movedRecords);
        lotLedgerService.moveOwner(lots.values(), movedShares, seller);
    }

    /**
     * 「매입가 넣기」 (FEATURE_2611_16): every check first, then the amounts (D3 · D6), then the photo (D2 · D13), then the
     * shares' lots and the debts (D6) — the photo is stored last so a refused request leaves no file behind.
     */
    @Override
    @Transactional
    public ReceiptCreateResult retypePrices(LocalDate movedOn, PurchasePriceRequest request, MultipartFile photo) {
        if (request == null || movedOn == null) {
            throw new IllegalArgumentException("영수증 내용을 보내세요.");
        }
        List<Long> ids = request.lines().stream().map(PurchasePriceRequest.Line::recordId).toList();
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("같은 줄을 두 번 넣었습니다.");
        }
        Map<Long, PurchaseRecord> found = purchaseRecordRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(PurchaseRecord::getId, r -> r));
        List<PurchaseRecord> records = new ArrayList<>();
        for (Long id : ids) {
            PurchaseRecord record = found.get(id);
            if (record == null) {
                throw new ResourceNotFoundException("PurchaseRecord", id);
            }
            if (!record.isPriceRetypable()) {
                throw new IllegalArgumentException("매입가를 넣을 수 없는 줄입니다.");
            }
            records.add(record);
        }
        boolean withPhoto = photo != null && !photo.isEmpty();
        boolean anyAmount = request.lines().stream().anyMatch(l -> l.totalAmount() != null || l.unitPrice() != null);
        if (!withPhoto && !anyAmount) {
            throw new IllegalArgumentException("바꿀 금액을 넣거나 영수증 사진을 올리세요.");
        }

        // D13 · D18 · D11 · D12 · D15: which receipt the photo goes to — decided before anything is written.
        PurchaseReceipt laterReceipt = null;
        Seller payer = null;
        SellerCard card = null;
        if (withPhoto) {
            Set<PurchaseRecord.ReceiptKind> kinds = records.stream().map(PurchaseRecord::receiptKind)
                    .collect(Collectors.toSet());
            if (kinds.contains(PurchaseRecord.ReceiptKind.RECEIPT)) {
                throw new IllegalArgumentException("사진 있는 영수증 입고가 있어 사진을 올릴 수 없습니다.");
            }
            if (kinds.contains(PurchaseRecord.ReceiptKind.RECEIPT_LATER)) {
                Set<Long> receiptIds = records.stream().filter(r -> r.getReceipt() != null)
                        .map(r -> r.getReceipt().getId()).collect(Collectors.toSet());
                if (kinds.size() > 1 || receiptIds.size() > 1) {
                    throw new IllegalArgumentException(
                            "사진은 같은 영수증의 「영수증 나중에」 입고만 체크했을 때 올릴 수 있습니다.");
                }
                laterReceipt = records.get(0).getReceipt();
            } else {
                Set<Long> sellerIds = records.stream().map(r -> r.getSeller().getId()).collect(Collectors.toSet());
                if (sellerIds.size() > 1) {
                    throw new IllegalArgumentException("사진을 넣으면 같은 판매자 입고만 묶을 수 있습니다.");
                }
                payer = records.get(0).getSeller();
                if (request.cardId() != null) {
                    card = sellerCardService.requireVisible(request.cardId());
                    if (!card.getSeller().getId().equals(payer.getId())) {
                        throw new IllegalArgumentException("이 입고 판매자의 카드가 아닙니다.");
                    }
                }
                if (records.stream().anyMatch(r -> !movedOn.equals(r.getPurchasedOn()))) {
                    throw new IllegalArgumentException("구매일이 화면 날짜와 다른 입고는 사진으로 묶을 수 없습니다.");
                }
            }
            imageValidator.validate(photo);
        }

        // D3 · D6 · D19: retype each line with an amount, in the order sent. The amounts are computed by
        // PurchaseRecord only (D135 ① round 32).
        Set<Long> productIds = new LinkedHashSet<>();
        Map<Long, StockMovement> lots = new LinkedHashMap<>();
        Set<Long> pricedLots = new LinkedHashSet<>();
        for (int index = 0; index < records.size(); index++) {
            PurchaseRecord record = records.get(index);
            PurchasePriceRequest.Line line = request.lines().get(index);
            if (line.totalAmount() == null && line.unitPrice() == null) {
                continue;                                                    // D7: a blank line keeps its amount
            }
            boolean reflect = line.reflectToBasePrice() == null || line.reflectToBasePrice();
            boolean latest = reflect && PurchaseRecord.isLatestMover(record,
                    purchaseRecordRepository.findBasePriceMovers(record.getProduct().getId()));
            PurchaseRecord saved = purchaseRecordRepository.save(
                    record.withRetypedAmount(line.totalAmount(), line.unitPrice(), latest));
            List<StockMovement> rows = stockMovementRepository.findByPurchaseRecordIdIn(List.of(record.getId()));
            stockMovementRepository.saveAll(rows.stream()       // FEATURE_2611_17 D22: a typed price drops 「미기입」
                    .map(m -> m.toBuilder().unitPrice(saved.getUnitPrice()).priceFromBaseAt(null).build()).toList());
            rows.forEach(m -> pricedLots.add(m.getId()));
            if (latest) {
                costPropagationService.updateBasePrice(saved);           // D3 — history PURCHASE_UPDATE
            }
            records.set(index, saved);
            productIds.add(record.getProduct().getId());
            if (record.getSourceMovement() != null) {
                lots.putIfAbsent(record.getSourceMovement().getId(), record.getSourceMovement());
                pricedLots.add(record.getSourceMovement().getId());
            }
        }

        Long receiptId = null;
        if (laterReceipt != null) {
            receiptId = attachPhoto(laterReceipt.getId(), photo).id();      // D13: the whole receipt gets the photo
        } else if (withPhoto) {
            // D2 · D12 · D15: a new receipt of that seller on the screen day; the lines leave found stock.
            PurchaseReceipt receipt = purchaseReceiptRepository.save(PurchaseReceipt.builder()
                    .purchasedOn(movedOn)
                    .payerSeller(payer)
                    .card(card)
                    .photoUrl(storePhoto(photo))
                    .createdBy(currentUsername())
                    .build());
            purchaseRecordRepository.saveAll(records.stream().map(r -> r.asReceiptLine(receipt)).toList());
            receiptId = receipt.getId();
        }

        purchaseRecordRepository.flush();
        stockMovementRepository.flush();
        // D6: a retyped share re-splits its undecided lot, then the touched products' debts are rewritten.
        lots.values().forEach(lotLedgerService::resplit);
        productIds.forEach(lotLedgerService::rebuildDebts);
        stockFillService.recostFilled(pricedLots);       // FEATURE_2611_22 D22 · D25: the orders those lots filled later
        return new ReceiptCreateResult(receiptId);
    }

    /**
     * The daily row popup (FEATURE_2611_17 D5): the checks of this request first, then the seller, the price or photo
     * ({@link #retypePrices} runs its own checks there), the picked receipt and the row's new day
     * ({@link StockDateEditService#moveRow} runs its own checks there, FEATURE_2611_21 D9) — one transaction, so a
     * refusal in any part leaves nothing behind.
     */
    @Override
    @Transactional
    public ReceiptCreateResult editDailyRow(LocalDate movedOn, DailyRowEditRequest request, MultipartFile photo) {
        if (request == null || movedOn == null) {
            throw new IllegalArgumentException("고칠 내용을 보내세요.");
        }
        if ((request.movementId() == null) == (request.shareRecordId() == null)) {
            throw new IllegalArgumentException("고칠 줄을 고르세요.");
        }
        StockMovement row = null;
        PurchaseRecord record;
        if (request.movementId() != null) {
            row = stockMovementRepository.findByIdIn(List.of(request.movementId())).stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("StockMovement", request.movementId()));
            record = row.isPriceRetypable() ? row.getPurchaseRecord() : null;
        } else {
            record = purchaseRecordRepository.findByIdIn(List.of(request.shareRecordId())).stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("PurchaseRecord", request.shareRecordId()));
            if (record.getSourceMovement() == null || !record.isPriceRetypable()) {
                throw new IllegalArgumentException("고칠 수 없는 줄입니다.");
            }
        }
        // D10 · D12 · D24: what this row lets the popup change — the same rules as the buttons.
        boolean sellerChangeable = row != null ? row.isSellerChangeableWithoutReceipt() : record.isNoReceiptShare();
        boolean openingPrice = row != null && row.isOpeningPriceEditable();
        if (record == null && !sellerChangeable && !openingPrice) {
            throw new IllegalArgumentException("고칠 수 없는 줄입니다.");
        }
        Seller current = row != null ? row.getSeller() : record.getSeller();
        boolean sellerMoves = request.sellerId() != null && !request.sellerId().equals(current.getId());
        boolean withAmount = request.totalAmount() != null || request.unitPrice() != null;
        boolean withPhoto = photo != null && !photo.isEmpty();
        if (sellerMoves && !sellerChangeable) {
            throw new IllegalArgumentException("판매자를 바꿀 수 없는 줄입니다.");
        }
        if (withAmount && record == null && !openingPrice) {
            throw new IllegalArgumentException("매입가를 넣을 수 없는 줄입니다.");
        }
        if ((withPhoto || request.receiptId() != null) && record == null) {
            throw new IllegalArgumentException("영수증을 붙일 수 없는 줄입니다.");             // D20
        }
        if (withPhoto && request.receiptId() != null) {
            throw new IllegalArgumentException("새 영수증 사진과 고른 영수증을 함께 넣을 수 없습니다.");
        }
        if (request.totalAmount() != null && request.unitPrice() != null) {
            throw new IllegalArgumentException("총액과 단가 중 하나만 입력하세요");
        }
        // FEATURE_2611_21 D9 · D13: a share has no day of its own, and a day cannot move with a receipt being bound.
        if (request.newMovedOn() != null && row == null) {
            throw new IllegalArgumentException("고칠 수 없는 줄입니다.");
        }
        boolean dateMoves = row != null && request.newMovedOn() != null
                && !request.newMovedOn().equals(row.getMovedOn());
        if (dateMoves && (withPhoto || request.receiptId() != null)) {
            throw new IllegalArgumentException("영수증에 묶인 입고는 날짜를 옮길 수 없습니다.");
        }
        if (!sellerMoves && !withAmount && !withPhoto && request.receiptId() == null && !dateMoves) {
            throw new IllegalArgumentException("바꿀 내용이 없습니다.");
        }
        // D23: a picked receipt = one of that day with its photo, paid by the seller this row has after the save.
        PurchaseReceipt picked = null;
        if (request.receiptId() != null) {
            if (record.receiptKind() != PurchaseRecord.ReceiptKind.NO_RECEIPT) {
                throw new IllegalArgumentException("영수증을 고를 수 없는 줄입니다.");          // D19
            }
            picked = purchaseReceiptRepository.findDetailById(request.receiptId())
                    .orElseThrow(() -> new ResourceNotFoundException("PurchaseReceipt", request.receiptId()));
            if (picked.getPhotoUrl() == null) {
                throw new IllegalArgumentException("사진 있는 영수증만 고를 수 있습니다.");
            }
            if (!movedOn.equals(picked.getPurchasedOn()) || !movedOn.equals(record.getPurchasedOn())) {
                throw new IllegalArgumentException("화면 날짜의 영수증만 고를 수 있습니다.");
            }
            Long sellerAfter = sellerMoves ? request.sellerId() : current.getId();
            if (!picked.getPayerSeller().getId().equals(sellerAfter)) {
                throw new IllegalArgumentException("고른 영수증의 결제 판매자와 판매자가 다릅니다.");
            }
        }

        // D5 · D10 · D21: the seller first, so the price, the photo's receipt and its card follow the new seller.
        if (sellerMoves) {
            changeSeller(new SellerChangeRequest(request.sellerId(),
                    row != null ? List.of(row.getId()) : List.of(),
                    row != null ? List.of() : List.of(record.getId())));
        }
        Long receiptId = null;
        if (record != null && (withAmount || withPhoto)) {
            receiptId = retypePrices(movedOn, new PurchasePriceRequest(request.cardId(),
                    List.of(new PurchasePriceRequest.Line(record.getId(), request.totalAmount(), request.unitPrice(),
                            request.reflectToBasePrice()))), photo).receiptId();
        } else if (withAmount) {
            priceOpeningStock(row, request.totalAmount(), request.unitPrice());
        }
        if (picked != null) {
            // D23 · D26: the purchase becomes one more line of the picked receipt (never merged into a line of it).
            PurchaseRecord saved = purchaseRecordRepository.findById(record.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("PurchaseRecord", record.getId()));
            purchaseRecordRepository.save(saved.asReceiptLine(picked));
            receiptId = picked.getId();
        }
        if (dateMoves) {
            stockDateEditService.moveRow(row.getId(), request.newMovedOn());      // FEATURE_2611_21 D9: the day last
        }
        return new ReceiptCreateResult(receiptId);
    }

    /**
     * FEATURE_2611_17 D17 · D18: an opening-stock row — or an 「기타」 intake row, FEATURE_2611_22 D38 — takes the typed
     * price (total → total / quantity); the registered price is filled only while it is empty (and the product's other
     * empty prices follow it, D22). The debts are rewritten; the cost of orders that already went out stays
     * (FEATURE_2611_16 D5), except the orders this lot filled later, which are costed again (FEATURE_2611_22 D22).
     */
    private void priceOpeningStock(StockMovement row, BigDecimal totalAmount, BigDecimal unitPrice) {
        BigDecimal unit = totalAmount != null
                ? PurchaseRecord.unitOfTotal(totalAmount, row.getQuantity()) : PurchaseRecord.typedUnit(unitPrice);
        StockMovement lot = stockMovementRepository.findById(row.getId())
                .orElseThrow(() -> new ResourceNotFoundException("StockMovement", row.getId()));
        stockMovementRepository.save(lot.toBuilder().unitPrice(unit).priceFromBaseAt(null).build());
        Long productId = row.getProduct().getId();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        if (product.getPrice() == null) {
            productRepository.save(product.toBuilder().price(PurchaseRecord.registeredPriceOf(unit)).build());
            basePriceFillService.fillEmptyPrices(productId);
        }
        stockMovementRepository.flush();
        lotLedgerService.rebuildDebts(productId);
        stockFillService.recostFilled(List.of(row.getId()));
    }

    /** What of one undecided intake row no receipt took yet (D121 ①): its record minus the assigned records. */
    private int undecidedLeft(StockMovement intake) {
        int assigned = purchaseRecordRepository.sumAssignedFrom(intake.getId());
        return intake.getQuantity() - assigned;
    }

    /**
     * The undecided intake rows of each line's product on {@code movedOn}, record order (D130) — the same product twice
     * → 400 · more than what is still undecided that day → 400 (D121 ①).
     */
    private Map<Long, List<StockMovement>> undecidedIntake(LocalDate movedOn, List<ReceiptLineRequest> lines) {
        Map<Long, Long> perProduct = lines.stream()
                .collect(Collectors.groupingBy(ReceiptLineRequest::productId, Collectors.counting()));
        if (perProduct.values().stream().anyMatch(count -> count > 1)) {
            throw new IllegalArgumentException("같은 상품을 두 번 넣었습니다.");
        }
        Map<Long, List<StockMovement>> intakeByProduct = new LinkedHashMap<>();
        for (ReceiptLineRequest line : lines) {
            List<StockMovement> intake = stockMovementRepository.findUndecidedIntake(line.productId(), movedOn);
            int left = intake.stream().mapToInt(this::undecidedLeft).sum();
            if (line.quantity() > left) {
                throw new IllegalArgumentException("남은 영수증 미등록 수량(" + left + "개)보다 많습니다.");
            }
            intakeByProduct.put(line.productId(), intake);
        }
        return intakeByProduct;
    }

    /**
     * Per line: take the quantity from the day's intake rows, lowest record order first (D130); each part becomes one
     * purchase record of {@code owner} pointing at its intake row (found stock when {@code foundStockAt} is set), and
     * the intake row's undecided record gets the same quantity as a minus row. No stock row is written (D125). Then the
     * touched rows are re-split and the debts rewritten (D131 · D132).
     */
    private void writeAssignment(Map<Long, List<StockMovement>> intakeByProduct, List<ReceiptLineRequest> lines,
                                 Seller owner, LocalDate purchasedOn, PurchaseReceipt receipt,
                                 LocalDateTime foundStockAt) {
        List<StockMovement> touched = new ArrayList<>();
        for (ReceiptLineRequest line : lines) {
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
            List<PurchaseRecord> assigned = PurchaseRecord.assignedParts(product, owner, purchasedOn,
                    line.totalAmount(), line.unitPrice(), line.reflectToBasePrice(), receipt, parts);
            for (int index = 0; index < parts.size(); index++) {
                PurchaseRecord record = foundStockAt == null
                        ? assigned.get(index) : assigned.get(index).asFoundStock(foundStockAt);
                costPropagationService.updateBasePrice(purchaseRecordRepository.save(record));
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
        // FEATURE_2611_22 D25: the orders those intake rows filled later take the new share prices.
        stockFillService.recostFilled(touched.stream().map(StockMovement::getId).toList());
    }

    /** 「영수증 없이 판매자 정하기」 · 「판매자 바꾸기」 seller — required, never back to 「미정」 (FEATURE_2610_19 D116 ⑤). */
    private Seller requireSeller(Long sellerId) {
        if (sellerId == null) {
            throw new IllegalArgumentException("판매자를 선택하세요.");
        }
        return sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller", sellerId));
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
                receipt.getPayerSeller().getSellerName(), cardAlias(receipt), firstProductName, lines.size(), total,
                receipt.getPhotoUrl() != null);
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
