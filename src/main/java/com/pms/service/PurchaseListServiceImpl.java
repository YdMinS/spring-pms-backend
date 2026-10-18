package com.pms.service;

import com.pms.domain.CoupangOrderLine;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.domain.PurchaseCheck;
import com.pms.domain.PurchaseCheckStatus;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
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
import com.pms.dto.response.UnmappedOrder;
import com.pms.config.CoupangProperties;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseCheckRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.cost.CostPropagationService;
import com.pms.service.listing.CellBomResolver;
import com.pms.service.stock.StockLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@link PurchaseListService} 구현. 추출/조회/입고/수동추가/조정.
 *
 * 클래스 기본 readOnly, 쓰기 메서드만 @Transactional 오버라이드.
 * Entity 는 @Setter 금지 — 갱신은 toBuilder 로 새 객체 생성.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PurchaseListServiceImpl implements PurchaseListService {

    /** 사입 대상 = 결제완료. 중립 상태로 판정한다(FEATURE_2609_26 / PLAN D4). */
    private static final OrderStatus PURCHASE_TARGET_STATUS = OrderStatus.PAID;

    /** Share states sent to the screens (FEATURE_2610_09 D57 · D67 · D79 · D80). */
    static final String STATE_OPEN = "OPEN";
    static final String STATE_SKIPPED_TODAY = "SKIPPED_TODAY";

    private final ShoppingListItemRepository shoppingListItemRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final PurchaseCheckRepository purchaseCheckRepository;
    private final OrderLineRepository orderLineRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    /** 셀 옵션의 구성품은 마스터를 타고 얻는다(2609_71) — 셀 BOM 사본을 읽지 않는다. */
    private final CellBomResolver cellBomResolver;
    private final ProductRepository productRepository;
    private final SellerRepository sellerRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final StockLedgerService stockLedgerService;
    private final CostPropagationService costPropagationService;
    private final PurchaseSettingService purchaseSettingService;
    private final CoupangProperties coupangProperties;
    private final Clock clock;

    @Override
    @Transactional
    public void extract() {
        // Needed quantity per (product × seller) share before the reload — the D85 「필요 0」 comparison base.
        Map<ShareKey, Integer> neededBefore = neededByShare(shoppingListItemRepository.findAll());

        // 1) Reset autoQty of every order-linked line → lines whose order shipped or was cancelled stay 0 after
        //    the reload. ⚠️ Manual lines (order_line_id IS NULL) are not touched.
        //    The reset and the reload both cover every seller (FEATURE_2610_09 D19).
        shoppingListItemRepository.resetAllAutoQty();

        // 2) 결제완료 주문을 옵션→BOM 전개해 (order_line, product) 라인 upsert.
        List<OrderLine> lines = purchaseTargetLines();
        Map<Long, String> vendorItemIds = vendorItemIdsByLine(lines);
        Map<Long, ProductListingOption> optionsByLine = optionsByLine(lines, vendorItemIds);
        // 🔴 BOM 은 주문 라인마다 읽지 않고 한 번에 모은다(2609_71 — 반복문 안의 forOption 은 N+1).
        Map<Long, CellBomResolver.Bom> boms = cellBomResolver.forOptions(distinctOptions(optionsByLine));
        for (OrderLine line : lines) {
            int q = line.purchasableQty();
            if (q <= 0) continue;

            ProductListingOption option = optionsByLine.get(line.getId());
            if (option == null) continue;        // 거울 행 없음 / 미매핑 → 조회에서 unmapped 로 노출

            CellBomResolver.Bom bom = boms.getOrDefault(option.getId(), CellBomResolver.Bom.UNMAPPED);
            if (bom.unmapped()) continue;        // 채널 전용 옵션 → 조회에서 unmapped 로 노출
            for (CellBomResolver.Line bomLine : bom.lines()) {
                int lineQty = q * bomLine.quantity();
                ShoppingListItem item = shoppingListItemRepository
                        .findByOrderLine_IdAndProduct_Id(line.getId(), bomLine.productId())
                        .map(existing -> existing.toBuilder().autoQty(lineQty).build())  // auto 만 교체, manual 보존
                        .orElseGet(() -> ShoppingListItem.builder()
                                .orderLine(line)
                                .product(bomLine.product())
                                .autoQty(lineQty)
                                .manualQty(0)
                                .build());
                shoppingListItemRepository.save(item);
            }
        }

        // 3) D85: a share that fell from above 0 to 0 gets a NEED_ZERO record dated today; a share above 0 has
        //    its NEED_ZERO record cancelled.
        recordNeedZero(neededBefore, neededByShare(shoppingListItemRepository.findAll()));
    }

    @Override
    public PurchaseListResponse getList(Long sellerId) {
        List<PurchaseProductGroup> items = new ArrayList<>();
        List<PurchaseProductGroup> skippedToday = new ArrayList<>();
        for (PurchaseProductGroup group : buildGroups(sellerId)) {
            List<String> states = group.shares().stream().map(PurchaseShare::state).toList();
            if (states.contains(STATE_OPEN)) {
                items.add(group);
            } else if (states.contains(STATE_SKIPPED_TODAY)) {
                skippedToday.add(group);
            }
        }
        return new PurchaseListResponse(items, skippedToday, buildUnmapped());
    }

    @Override
    public List<PurchaseProductGroup> getCompletedList(LocalDate from, LocalDate to, Long sellerId) {
        // D57 · D78: a row is completed when every visible share is PURCHASED / NOT_NEEDED / NEED_ZERO.
        // D63 · D81 · D85: its day = the latest processing day among the shares.
        List<PurchaseProductGroup> completed = new ArrayList<>();
        for (PurchaseProductGroup group : buildGroups(sellerId)) {
            boolean allDone = group.shares().stream().allMatch(share -> share.checkedOn() != null);
            if (!allDone) continue;
            LocalDate day = group.shares().stream().map(PurchaseShare::checkedOn)
                    .max(Comparator.naturalOrder()).orElseThrow();
            if ((from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to))) {
                completed.add(group);
            }
        }
        return completed;
    }

    /**
     * Groups every shopping_list_item by product, splits each product into (product × seller) shares and drops the
     * shares that are not shown (D80: needed has always been 0). A product with no shown share is left out.
     *
     * <p>{@code sellerId} given (D2 · D6): the row's numbers, shares and lines are that seller's only.
     * {@code sellerId} null (D22 · D58): the numbers add the shares up, each share's purchase capped at its own need.
     *
     * <p>⚠️ No N+1: product names, seller names, channel labels, purchase records and processing records are each
     * read once for the whole list and joined through maps.
     */
    private List<PurchaseProductGroup> buildGroups(Long sellerId) {
        List<ShoppingListItem> items = shoppingListItemRepository.findAll();
        if (items.isEmpty()) {
            return List.of();
        }

        List<Long> productIds = items.stream().map(i -> i.getProduct().getId()).distinct().toList();
        Map<ShareKey, Integer> purchasedByShare = new HashMap<>();
        for (PurchaseRecord record : purchaseRecordRepository.findByProduct_IdIn(productIds)) {
            purchasedByShare.merge(new ShareKey(record.getProduct().getId(), record.getSeller().getId()),
                    record.getQuantity(), Integer::sum);
        }
        Map<ShareKey, List<PurchaseCheck>> checksByShare = purchaseCheckRepository
                .findByProduct_IdInAndCancelledAtIsNull(productIds).stream()
                .collect(Collectors.groupingBy(c -> new ShareKey(c.getProduct().getId(), c.getSeller().getId())));
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        Map<Long, ChannelLabel> channels = channelLabels(items);
        Map<Long, String> sellerNames = sellerNames(items, channels);
        LocalDate today = today();

        // product 단위 그룹화 (입력 순서 보존).
        Map<Long, List<ShoppingListItem>> byProduct = items.stream()
                .collect(Collectors.groupingBy(i -> i.getProduct().getId(), LinkedHashMap::new, Collectors.toList()));

        List<PurchaseProductGroup> groups = new ArrayList<>();
        for (Map.Entry<Long, List<ShoppingListItem>> entry : byProduct.entrySet()) {
            Long productId = entry.getKey();

            // Seller → that seller's lines of this product, seller id ascending (share order on screen).
            Map<Long, List<ShoppingListItem>> bySeller = new TreeMap<>();
            for (ShoppingListItem item : entry.getValue()) {
                Long lineSellerId = sellerIdOf(item, channels);
                if (lineSellerId == null) continue;
                bySeller.computeIfAbsent(lineSellerId, k -> new ArrayList<>()).add(item);
            }

            List<PurchaseShare> shares = new ArrayList<>();
            List<ShoppingListItem> shownLines = new ArrayList<>();
            for (Map.Entry<Long, List<ShoppingListItem>> sellerEntry : bySeller.entrySet()) {
                Long shareSellerId = sellerEntry.getKey();
                if (sellerId != null && !sellerId.equals(shareSellerId)) continue;
                ShareKey key = new ShareKey(productId, shareSellerId);
                int needed = sellerEntry.getValue().stream().mapToInt(ShoppingListItem::neededQty).sum();
                int purchased = purchasedByShare.getOrDefault(key, 0);
                PurchaseShare share = toShare(shareSellerId, sellerNames.get(shareSellerId), needed, purchased,
                        checksByShare.getOrDefault(key, List.of()), today);
                if (share == null) continue;   // D80: needed has always been 0 → shown nowhere
                shares.add(share);
                shownLines.addAll(sellerEntry.getValue());
            }
            if (shares.isEmpty()) continue;

            int needed = shares.stream().mapToInt(PurchaseShare::neededQty).sum();
            int purchased;
            int remaining;
            if (sellerId != null) {
                purchased = shares.get(0).purchasedQty();             // D2: that seller's records as they are
                remaining = needed - purchased;
            } else {
                purchased = shares.stream()                           // D58: capped at each share's own need
                        .mapToInt(sh -> Math.min(sh.purchasedQty(), sh.neededQty())).sum();
                remaining = shares.stream()                           // D22: shortfalls only
                        .mapToInt(sh -> Math.max(0, sh.neededQty() - sh.purchasedQty())).sum();
            }

            // Lines keep the shopping list order (D6: a chosen seller sees only that seller's lines).
            List<PurchaseLine> lines = entry.getValue().stream()
                    .filter(shownLines::contains)
                    .map(li -> toLine(li, channels, sellerNames))
                    .toList();

            Product product = products.get(productId);
            groups.add(new PurchaseProductGroup(
                    productId,
                    product != null ? product.getProductName() : null,
                    product != null ? product.getBrand() : null,
                    needed,
                    purchased,
                    remaining,
                    shares,
                    lines));
        }
        return groups;
    }

    /**
     * One share's state from its records in effect (D57 · D60 · D66 · D67 · D80 · D82 · D85).
     * <ol>
     *   <li>the latest screen-made record: PURCHASED / NOT_NEEDED count while the current need is not larger than
     *       the need written on it; TOMORROW counts on its own check day only</li>
     *   <li>otherwise a NEED_ZERO record counts while the current need is 0</li>
     *   <li>otherwise OPEN when something is needed; nothing (null = not shown) when the need is 0</li>
     * </ol>
     */
    private PurchaseShare toShare(Long shareSellerId, String sellerName, int needed, int purchased,
                                  List<PurchaseCheck> checks, LocalDate today) {
        PurchaseCheck manual = checks.stream()
                .filter(c -> c.getStatus() != PurchaseCheckStatus.NEED_ZERO)
                .max(Comparator.comparing(PurchaseCheck::getId)).orElse(null);
        PurchaseCheck needZero = checks.stream()
                .filter(c -> c.getStatus() == PurchaseCheckStatus.NEED_ZERO)
                .max(Comparator.comparing(PurchaseCheck::getId)).orElse(null);

        String state;
        Long checkId = null;
        LocalDate checkedOn = null;
        if (manual != null && manual.getStatus() != PurchaseCheckStatus.TOMORROW
                && needed <= manual.getNeededQty()) {
            state = manual.getStatus().name();
            checkId = manual.getId();
            checkedOn = manual.getCheckedOn();
        } else if (manual != null && manual.getStatus() == PurchaseCheckStatus.TOMORROW
                && manual.getCheckedOn().equals(today)) {
            state = STATE_SKIPPED_TODAY;
            checkId = manual.getId();
        } else if (needZero != null && needed == 0) {
            state = PurchaseCheckStatus.NEED_ZERO.name();
            checkedOn = needZero.getCheckedOn();
        } else if (needed > 0) {
            state = STATE_OPEN;
        } else {
            return null;
        }
        return new PurchaseShare(shareSellerId, sellerName, needed, purchased, needed - purchased,
                purchased >= needed, state, checkId, checkedOn);
    }

    @Override
    @Transactional
    public PurchaseRecordResult addPurchase(PurchaseRecordRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.productId()));
        Seller seller = sellerRepository.findById(request.sellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.sellerId()));

        // 금액 계산 규칙은 엔티티 팩토리 하나에 모여 있다(FEATURE_2609_28 / PLAN D1·D2).
        PurchaseRecord record = PurchaseRecord.of(product, seller, request);
        // D65 · D84: the stock link setting decides whether stock is written, and the record keeps that value.
        boolean stockLinked = purchaseSettingService.isStockLinked();
        PurchaseRecord saved = purchaseRecordRepository.save(record.toBuilder().stockLinked(stockLinked).build());

        // ① 기준가 갱신(2609_28 D4). reflectToBasePrice·단가·수량 조건은 서비스가 소유한다.
        // ⚠️ 여기서 파급(②=셀 판매가 재계산)은 일어나지 않는다 — 사용자가 preview → apply 로 확정한다.
        costPropagationService.updateBasePrice(saved);

        // Stock link on: stock is written unless recordStock=false (PLAN 2609_29 D19 contract kept).
        // Stock link off: purchase record only (FEATURE_2610_09 D65).
        // D17: a negative correction means "the amount was wrong", not "goods left" — STOCK_IN rejects it.
        boolean wantStock = stockLinked && ((request.recordStock() == null) || request.recordStock());
        boolean stockRecorded = wantStock && request.quantity() > 0;
        if (stockRecorded) {
            // ⚠️ record 는 @Transactional(REQUIRED) 이라 이 트랜잭션에 합류한다 → 재고 실패 시 구매기록도 롤백.
            //    REQUIRES_NEW 로 바꾸면 "돈만 남고 물건은 없는" 행이 조용히 생긴다.
            // ⚠️ 단가를 넘기지 않는다(D16): 원장이 구매기록에서 승계한다. 여기서 계산하면 규칙이 두 벌이 된다.
            stockLedgerService.record(new StockMovementRequest(
                    request.productId(),          // productId
                    request.sellerId(),           // sellerId
                    StockMovementType.STOCK_IN,   // movementType
                    request.quantity(),           // quantity
                    StockReason.PURCHASE,         // reason
                    null,                         // reasonNote
                    null,                         // unitPrice — 구매기록에서 승계
                    null,                         // orderClaimId
                    saved.getId(),                // purchaseRecordId
                    request.purchasedOn()));      // movedOn
        }
        return new PurchaseRecordResult(saved.getId(), stockRecorded);
    }

    @Override
    public List<PurchaseRecordView> recentPurchases(Long productId, Long sellerId, int limit) {
        return purchaseRecordRepository.findRecentByProduct(productId, sellerId, PageRequest.of(0, limit)).stream()
                .map(r -> new PurchaseRecordView(r.getId(), r.getPurchasedOn(), r.getQuantity(),
                        r.getTotalAmount(), r.getUnitPrice(), Boolean.TRUE.equals(r.getReflectToBasePrice()),
                        r.getSeller().getSellerName()))
                .toList();
    }

    @Override
    @Transactional
    public void addManual(ManualItemRequest request) {
        // D11 · D16: a manual line always belongs to a seller; one manual line per (product × seller), accumulated.
        Seller seller = sellerRepository.findById(request.sellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.sellerId()));
        ShoppingListItem item = shoppingListItemRepository
                .findByOrderLineIsNullAndProduct_IdAndSeller_Id(request.productId(), request.sellerId())
                .map(existing -> existing.toBuilder()
                        .manualQty(existing.getManualQty() + request.quantity())   // 누적
                        .build())
                .orElseGet(() -> {
                    Product product = productRepository.findById(request.productId())
                            .orElseThrow(() -> new ResourceNotFoundException("Product", request.productId()));
                    return ShoppingListItem.builder()
                            .orderLine(null)
                            .product(product)
                            .seller(seller)
                            .autoQty(0)
                            .manualQty(request.quantity())
                            .build();
                });
        shoppingListItemRepository.save(item);
    }

    @Override
    @Transactional
    public void adjustManual(Long itemId, ManualAdjustRequest request) {
        ShoppingListItem item = requireManualLine(itemId);
        shoppingListItemRepository.save(item.toBuilder()
                .manualQty(request.manualQty())   // absolute replacement, 1 or more (DTO validation)
                .build());
    }

    @Override
    @Transactional
    public void changeManualSeller(Long itemId, ManualSellerRequest request) {
        ShoppingListItem item = requireManualLine(itemId);
        Seller seller = sellerRepository.findById(request.sellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.sellerId()));
        if (item.getSeller() != null && seller.getId().equals(item.getSeller().getId())) {
            return;
        }
        // D14 · D24 · D26: never merge two manual lines — refuse and point at the existing line.
        if (shoppingListItemRepository.findByOrderLineIsNullAndProduct_IdAndSeller_Id(
                item.getProduct().getId(), seller.getId()).isPresent()) {
            throw new BusinessException(
                    seller.getSellerName() + " 몫 수동 줄이 이미 있습니다. 그 줄의 수량을 고치세요.",
                    HttpStatus.CONFLICT);
        }
        shoppingListItemRepository.save(item.toBuilder().seller(seller).build());
    }

    @Override
    @Transactional
    public void deleteManual(Long itemId) {
        shoppingListItemRepository.delete(requireManualLine(itemId));
    }

    @Override
    @Transactional
    public void check(PurchaseCheckRequest request) {
        if (request.status() == PurchaseCheckStatus.NEED_ZERO) {
            throw new BusinessException("처리 상태가 올바르지 않습니다.", HttpStatus.BAD_REQUEST);
        }
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.productId()));
        Seller seller = sellerRepository.findById(request.sellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.sellerId()));

        LocalDateTime now = now();
        // D62: at most one screen-made record of a share stays in effect.
        for (PurchaseCheck previous : purchaseCheckRepository
                .findByProduct_IdAndSeller_IdAndCancelledAtIsNull(product.getId(), seller.getId())) {
            if (previous.getStatus() != PurchaseCheckStatus.NEED_ZERO) {
                purchaseCheckRepository.save(previous.toBuilder().cancelledAt(now).build());
            }
        }
        purchaseCheckRepository.save(PurchaseCheck.builder()
                .product(product)
                .seller(seller)
                .status(request.status())
                .checkedOn(today())
                .neededQty(currentNeeded(product.getId(), seller.getId()))
                .createdBy(currentUsername())
                .build());
    }

    @Override
    @Transactional
    public void cancelCheck(Long checkId) {
        PurchaseCheck check = purchaseCheckRepository.findById(checkId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseCheck", checkId));
        if (check.getStatus() == PurchaseCheckStatus.NEED_ZERO) {
            throw new BusinessException("자동 기록은 취소할 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
        if (check.getCancelledAt() != null) {
            return;
        }
        purchaseCheckRepository.save(check.toBuilder().cancelledAt(now()).build());
    }

    // --- helpers ---

    /** 라인 토글의 채널 칩 라벨 = 판매자 × 플랫폼 ("A상사/쿠팡"). 수동 라인은 이 값이 없다. */
    private record ChannelLabel(Long marketplaceAccountId, Long sellerId, String sellerName, String platform) {}

    /** (product × seller) — one share of the purchase list (FEATURE_2610_09 D2). */
    private record ShareKey(Long productId, Long sellerId) {}

    /**
     * 계정 id → 채널 라벨. 계정 수만큼만 조회한다 — 라인마다 LAZY 프록시의 getSellerName() 을 부르면
     * 라인 수만큼 쿼리가 나간다(PLAN 2609_29 D8 ⑤).
     */
    private Map<Long, ChannelLabel> channelLabels(List<ShoppingListItem> items) {
        List<Long> accountIds = items.stream()
                .map(ShoppingListItem::getOrderLine)
                .filter(Objects::nonNull)
                // Order.marketplaceAccount 는 nullable = false 라 주문 라인이면 항상 있다.
                .map(line -> line.getOrder().getMarketplaceAccount().getId())
                .distinct()
                .toList();
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return marketplaceAccountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(MarketplaceAccount::getId,
                        a -> new ChannelLabel(a.getId(), a.getSeller().getId(), a.getSeller().getSellerName(),
                                a.getPlatform().name())));
    }

    /** Seller id → name for every seller a line belongs to: channel labels first, manual lines' sellers in one query. */
    private Map<Long, String> sellerNames(List<ShoppingListItem> items, Map<Long, ChannelLabel> channels) {
        Map<Long, String> names = new HashMap<>();
        channels.values().forEach(c -> names.put(c.sellerId(), c.sellerName()));
        Set<Long> manualSellerIds = items.stream()
                .filter(i -> i.getOrderLine() == null && i.getSeller() != null)
                .map(i -> i.getSeller().getId())
                .filter(id -> !names.containsKey(id))
                .collect(Collectors.toSet());
        if (!manualSellerIds.isEmpty()) {
            sellerRepository.findAllById(manualSellerIds)
                    .forEach(seller -> names.put(seller.getId(), seller.getSellerName()));
        }
        return names;
    }

    /** D16: an order line's seller comes from its account, a manual line's from its own column. */
    private static Long sellerIdOf(ShoppingListItem item, Map<Long, ChannelLabel> channels) {
        OrderLine line = item.getOrderLine();
        if (line == null) {
            return item.getSeller() != null ? item.getSeller().getId() : null;
        }
        ChannelLabel channel = channels.get(line.getOrder().getMarketplaceAccount().getId());
        return channel != null ? channel.sellerId() : null;
    }

    /** Needed quantity per (product × seller) share of the given lines. */
    private Map<ShareKey, Integer> neededByShare(List<ShoppingListItem> items) {
        Map<Long, ChannelLabel> channels = channelLabels(items);
        Map<ShareKey, Integer> needed = new HashMap<>();
        for (ShoppingListItem item : items) {
            Long lineSellerId = sellerIdOf(item, channels);
            if (lineSellerId == null) continue;
            needed.merge(new ShareKey(item.getProduct().getId(), lineSellerId), item.neededQty(), Integer::sum);
        }
        return needed;
    }

    /**
     * D85: after the reload, a share whose need fell from above 0 to 0 gets a NEED_ZERO record dated today
     * (unless one is already in effect); a share whose need is above 0 has its NEED_ZERO record cancelled.
     */
    private void recordNeedZero(Map<ShareKey, Integer> before, Map<ShareKey, Integer> after) {
        Map<ShareKey, PurchaseCheck> active = new HashMap<>();
        for (PurchaseCheck check : purchaseCheckRepository.findByStatusAndCancelledAtIsNull(PurchaseCheckStatus.NEED_ZERO)) {
            active.put(new ShareKey(check.getProduct().getId(), check.getSeller().getId()), check);
        }
        LocalDate today = today();
        LocalDateTime now = now();
        for (Map.Entry<ShareKey, Integer> entry : after.entrySet()) {
            ShareKey key = entry.getKey();
            int neededNow = entry.getValue();
            PurchaseCheck existing = active.get(key);
            if (neededNow > 0 && existing != null) {
                purchaseCheckRepository.save(existing.toBuilder().cancelledAt(now).build());
            } else if (neededNow == 0 && before.getOrDefault(key, 0) > 0 && existing == null) {
                purchaseCheckRepository.save(PurchaseCheck.builder()
                        .product(productRepository.getReferenceById(key.productId()))
                        .seller(sellerRepository.getReferenceById(key.sellerId()))
                        .status(PurchaseCheckStatus.NEED_ZERO)
                        .checkedOn(today)
                        .neededQty(0)
                        .createdBy(currentUsername())
                        .build());
            }
        }
    }

    /** The share's needed quantity right now — written on a new processing record (D60 · D62). */
    private int currentNeeded(Long productId, Long sellerId) {
        return neededByShare(shoppingListItemRepository.findByProductId(productId))
                .getOrDefault(new ShareKey(productId, sellerId), 0);
    }

    /** D13 · D23 · D77: only manual lines can be edited, moved or deleted. */
    private ShoppingListItem requireManualLine(Long itemId) {
        ShoppingListItem item = shoppingListItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("ShoppingListItem", itemId));
        if (item.getOrderLine() != null) {
            throw new BusinessException("수동 줄만 바꿀 수 있습니다.", HttpStatus.BAD_REQUEST);
        }
        return item;
    }

    /** KST calendar day — 「오늘 넘김」 and the completed tab's day are KST days (D63 · D67). */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SyncWindow.KST);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), SyncWindow.KST);
    }

    /** Same helper as {@code StockLedgerServiceImpl.currentUsername()} — batch context yields null. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }

    private List<OrderLine> purchaseTargetLines() {
        // 동기화 윈도우(syncDays) 밖 주문은 상태가 갱신되지 않아 stale 결제완료로 남을 수 있으므로,
        // 구매목록 추출도 같은 윈도우(orders.ordered_at 기준)로 제한한다.
        // 🔴 판매자 필터 없음 — 동기화는 항상 전체다(PLAN 2609_29 D11).
        LocalDateTime from = LocalDate.now().minusDays(coupangProperties.getSyncDays()).atStartOfDay();
        return orderLineRepository.findRecentByStatus(PURCHASE_TARGET_STATUS, from);
    }

    /**
     * 라인 id → 옵션 매칭키(vendorItemId). 거울 행을 라인마다 다시 읽지 않고 한 번에 가져온다
     * (FEATURE_2609_26 / 04 §3-3).
     */
    private Map<Long, String> vendorItemIdsByLine(List<OrderLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = lines.stream().map(OrderLine::getId).toList();
        return coupangOrderLineRepository.findByOrderLine_IdIn(ids).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), CoupangOrderLine::getVendorItemId,
                        (a, b) -> a));
    }

    /** Line id → 11st option number ({@code prdStckNo}) — the unmapped group key of 11st lines (D17). */
    private Map<Long, String> elevenstItemIdsByLine(List<OrderLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = lines.stream().map(OrderLine::getId).toList();
        Map<Long, String> byLine = new HashMap<>();
        for (ElevenstOrderLine mirror : elevenstOrderLineRepository.findByOrderLine_IdIn(ids)) {
            if (mirror.getPrdStckNo() != null) {
                byLine.putIfAbsent(mirror.getOrderLine().getId(), mirror.getPrdStckNo());
            }
        }
        return byLine;
    }

    private PurchaseLine toLine(ShoppingListItem li, Map<Long, ChannelLabel> channels, Map<Long, String> sellerNames) {
        OrderLine line = li.getOrderLine();
        ChannelLabel channel = line == null
                ? null
                : channels.get(line.getOrder().getMarketplaceAccount().getId());
        Long lineSellerId = sellerIdOf(li, channels);
        return new PurchaseLine(
                li.getId(),
                line != null ? line.getId() : null,
                line != null ? "ORDER" : "MANUAL",
                line != null ? line.getOrder().getExternalOrderId() : null,
                channel != null ? channel.marketplaceAccountId() : null,
                lineSellerId,
                lineSellerId != null ? sellerNames.get(lineSellerId) : null,
                channel != null ? channel.platform() : null,
                li.getAutoQty(),
                li.getManualQty(),
                li.neededQty());
    }

    /**
     * Paid lines whose option is unmapped or whose BOM is empty, grouped by the market option number — Coupang
     * {@code vendorItemId} or 11st {@code prdStckNo} (FEATURE_2610_17 / D17).
     */
    private List<UnmappedOrder> buildUnmapped() {
        List<OrderLine> lines = purchaseTargetLines();
        Map<Long, String> vendorItemIds = vendorItemIdsByLine(lines);
        Map<Long, String> elevenstItemIds = elevenstItemIdsByLine(lines);
        Map<Long, ProductListingOption> optionsByLine = optionsByLine(lines, vendorItemIds);
        Map<Long, CellBomResolver.Bom> boms = cellBomResolver.forOptions(distinctOptions(optionsByLine));
        Map<String, List<OrderLine>> byItem = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            if (line.purchasableQty() <= 0) continue;
            String vendorItemId = vendorItemIds.getOrDefault(line.getId(), elevenstItemIds.get(line.getId()));
            if (vendorItemId == null) continue;
            // 2609_71: 미매핑 = 옵션을 못 찾았거나, 마스터에 연결되지 않아 구성품을 알 수 없거나
            // (채널 전용 옵션), 마스터 옵션에 구성품이 하나도 없는 경우. 셋 다 살 것을 뽑을 수 없다.
            ProductListingOption option = optionsByLine.get(line.getId());
            boolean mapped = option != null
                    && !boms.getOrDefault(option.getId(), CellBomResolver.Bom.UNMAPPED).isEmpty();
            if (!mapped) {
                byItem.computeIfAbsent(vendorItemId, k -> new ArrayList<>()).add(line);
            }
        }
        return byItem.entrySet().stream()
                .map(e -> {
                    List<OrderLine> group = e.getValue();
                    int qty = group.stream().mapToInt(OrderLine::purchasableQty).sum();
                    return new UnmappedOrder(e.getKey(), group.get(0).getItemName(), qty, group.size());
                })
                .toList();
    }

    /**
     * Order line → channel option (FEATURE_2610_17 / D17): the line's own option column first; only when it is empty,
     * the Coupang {@code vendorItemId} lookup as before (one query per vendorItemId even when several orders share it).
     * A line without an option is absent from the map — that line is unmapped.
     */
    private Map<Long, ProductListingOption> optionsByLine(List<OrderLine> lines, Map<Long, String> vendorItemIds) {
        Map<String, Optional<ProductListingOption>> cache = new HashMap<>();
        Map<Long, ProductListingOption> byLine = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            if (line.getProductListingOption() != null) {
                byLine.put(line.getId(), line.getProductListingOption());
                continue;
            }
            String vendorItemId = vendorItemIds.get(line.getId());
            if (vendorItemId == null) continue;   // 거울 행 없음 = 옵션 매칭 키가 없다
            cache.computeIfAbsent(vendorItemId, productListingOptionRepository::findByPlatformOptionId)
                    .ifPresent(option -> byLine.put(line.getId(), option));
        }
        return byLine;
    }

    /** 같은 옵션이 여러 라인에 걸려 있어도 BOM 조회는 한 번이면 된다. */
    private static List<ProductListingOption> distinctOptions(Map<Long, ProductListingOption> optionsByLine) {
        Map<Long, ProductListingOption> byId = new LinkedHashMap<>();
        optionsByLine.values().forEach(option -> byId.putIfAbsent(option.getId(), option));
        return List.copyOf(byId.values());
    }
}
