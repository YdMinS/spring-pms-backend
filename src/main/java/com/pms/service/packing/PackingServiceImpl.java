package com.pms.service.packing;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.Package;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.domain.ShipmentParcelStatusLog;
import com.pms.dto.request.BoxCandidateRequest;
import com.pms.dto.request.OutboundConfirmRequest;
import com.pms.dto.request.ParcelCompleteRequest;
import com.pms.dto.response.BarcodeLookupResponse;
import com.pms.dto.response.OutboundUnexpandedView;
import com.pms.dto.response.PackingHistoryView;
import com.pms.dto.response.PackingRemainingItem;
import com.pms.dto.response.PackingScanResponse;
import com.pms.dto.response.ParcelCloseResponse;
import com.pms.dto.response.ParcelCompleteResponse;
import com.pms.dto.response.PendingParcelView;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.repository.ShipmentParcelStatusLogRepository;
import com.pms.service.InvoiceNumbers;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.stock.RemainingLine;
import com.pms.service.stock.StockOutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link PackingService} implementation (FEATURE_2609_40 / PLAN D9 ~ D19 · D27 ~ D32 · FEATURE_2611_06 / PLAN D1 ·
 * D4 ~ D7).
 *
 * <p>🔴 <b>The remaining quantity is {@code required − STOCK_OUT total} and nothing else</b> (D28). Completion writes
 * the stock out on the spot, so packed quantities are already taken off — subtracting {@code shipment_parcel_item}
 * from the remaining quantity <b>again would count it twice</b>. So this class never uses
 * {@code parcelItemRepository} for the remaining quantity (it reads it only for the idempotent complete response and
 * for a box change's packed quantity and box memory, FEATURE_2611_06 D5).
 *
 * <p>🔴 <b>Never filter lines with {@code isTerminal()}</b> (D27). {@code SHIPPED} (shipping instructed) counts as
 * terminal, so filtering with it drops every line at packing time — the only lines left out are
 * {@code isFullyCancelled()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PackingServiceImpl implements PackingService {

    private final ShipmentParcelRepository parcelRepository;
    private final ShipmentParcelItemRepository parcelItemRepository;
    private final OrderLineRepository orderLineRepository;
    private final ProductRepository productRepository;
    private final PackageRepository packageRepository;
    private final StockOutService stockOutService;
    private final BoxRecipeService boxRecipeService;
    private final MasterChannelConfigService masterChannelConfigService;
    private final ParcelStatusRecorder parcelStatusRecorder;
    private final ShipmentParcelStatusLogRepository statusLogRepository;

    // ── 조회 ──────────────────────────────────────────────────────────────────

    @Override
    public PackingScanResponse scan(String invoiceNumber) {
        // 스캐너·손입력 어느 쪽이든 하이픈·공백은 벗겨서 찾는다 — 저장값이 정규화돼 있으므로
        // 벗기지 않으면 "2558-2825-3026" 으로 친 사용자가 자기 박스를 못 연다.
        String normalized = InvoiceNumbers.normalize(invoiceNumber);
        String scanned = normalized == null ? "" : normalized;
        ShipmentParcel parcel = parcelRepository.findByInvoiceNumberOrderByIdAsc(scanned).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "송장번호를 찾을 수 없습니다: " + scanned, HttpStatus.NOT_FOUND));

        Long shipmentId = parcel.getOrderShipment().getId();
        List<ShipmentParcel> siblings = parcelRepository.findByOrderShipment_IdOrderByParcelSeqAsc(shipmentId);
        if (parcel.getStatus() != ParcelStatus.PENDING) {
            // 이미 닫힌 박스는 예외가 아니다 — 화면이 상태를 보고 안내한다.
            return new PackingScanResponse(parcelView(parcel, siblings.size()), orderView(parcel),
                    List.of(), List.of(), List.of(), false);
        }

        List<OrderLine> lines = packingLines(shipmentId);
        Map<Long, OrderLine> linesById = byId(lines);
        List<RemainingLine> remainingLines = stockOutService.remaining(lines);

        List<PackingRemainingItem> remaining = new ArrayList<>();
        List<OutboundUnexpandedView> unexpanded = new ArrayList<>();
        Map<Long, Product> products = productsOf(remainingLines);
        for (RemainingLine remainingLine : remainingLines) {
            OrderLine line = linesById.get(remainingLine.orderLineId());
            if (remainingLine.failed()) {
                unexpanded.add(new OutboundUnexpandedView(line.getId(),
                        line.getOrder().getExternalOrderId(), line.getItemName(),
                        remainingLine.failure().name()));
                continue;
            }
            remainingLine.products().stream()
                    .filter(p -> p.remainingQty() > 0)
                    .forEach(p -> {
                        // 지워진 물품은 맵에 없다 — 바코드·사진 없이 그대로 내린다(예전 barcodes.get 도 null 을 허용했다).
                        Product product = products.get(p.productId());
                        remaining.add(new PackingRemainingItem(line.getId(), line.getItemName(),
                                p.productId(), p.productName(),
                                product == null ? null : product.getBarcodeId(),
                                p.remainingQty(),
                                product == null ? null : product.getImageUrl()));
                    });
        }

        return new PackingScanResponse(
                parcelView(parcel, siblings.size()),
                orderView(parcel),
                remaining,
                // 아직 담은 것이 없으므로 추천할 조합도 없다 — 화면이 담기 시작하면 다시 묻는다(D23).
                List.of(),
                unexpanded,
                isLastParcel(siblings, totalRemainingQty(remainingLines)));
    }

    @Override
    public List<PendingParcelView> pending(Long sellerId) {
        List<ShipmentParcel> parcels = parcelRepository.findByStatusAndSeller(ParcelStatus.PENDING, sellerId);
        if (parcels.isEmpty()) {
            return List.of();
        }

        Set<Long> shipmentIds = parcels.stream()
                .map(p -> p.getOrderShipment().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 묶음마다 다시 읽지 않는다 — 라인도 잔량도 한 번에 구한다(N+1 금지).
        List<OrderLine> lines = orderLineRepository.findByOrderShipment_IdIn(shipmentIds).stream()
                .filter(line -> !line.isFullyCancelled())
                .toList();
        Map<Long, Long> shipmentIdByLine = lines.stream()
                .collect(Collectors.toMap(OrderLine::getId, line -> line.getOrderShipment().getId()));

        Map<Long, Integer> remainingByShipment = new LinkedHashMap<>();
        for (RemainingLine remainingLine : stockOutService.remaining(lines)) {
            Long shipmentId = shipmentIdByLine.get(remainingLine.orderLineId());
            remainingByShipment.merge(shipmentId, remainingLine.totalRemainingQty(), Integer::sum);
        }

        Map<Long, Long> parcelCountByShipment = parcelRepository.findByOrderShipment_IdIn(shipmentIds).stream()
                .collect(Collectors.groupingBy(p -> p.getOrderShipment().getId(), Collectors.counting()));

        List<PendingParcelView> views = new ArrayList<>();
        for (ShipmentParcel parcel : parcels) {
            Long shipmentId = parcel.getOrderShipment().getId();
            int remaining = remainingByShipment.getOrDefault(shipmentId, 0);
            if (remaining <= 0) {
                // 🔴 D31: 백필이 만든 「할 일 없는 PENDING 박스」를 여기서 떨군다. 상태가 아니라 잔량이 기준이다.
                continue;
            }
            views.add(new PendingParcelView(
                    parcel.getId(),
                    parcel.getInvoiceNumber(),
                    parcel.getCarrierName(),
                    parcel.getParcelSeq(),
                    parcelCountByShipment.getOrDefault(shipmentId, 1L).intValue(),
                    parcel.getOrderShipment().getOrder().getExternalOrderId(),
                    sellerNameOf(parcel),
                    remaining,
                    parcel.getOrderShipment().getOrder().getOrderedAt()));
        }
        return views;
    }

    @Override
    public BarcodeLookupResponse barcode(String value, Long parcelId) {
        if (parcelId == null) {
            // 🔴 박스가 없으면 「이 주문에 없는 물품」을 판정할 기준(그 박스의 잔량 목록)이 없다.
            throw new IllegalArgumentException("박스를 먼저 스캔해야 합니다");
        }
        ShipmentParcel parcel = parcel(parcelId);
        Product product = productRepository.findByBarcodeId(value == null ? "" : value.trim()).orElse(null);
        if (product == null) {
            return BarcodeLookupResponse.notRegistered();
        }

        List<OrderLine> lines = packingLines(parcel.getOrderShipment().getId());
        boolean inThisParcel = stockOutService.remaining(lines).stream()
                .anyMatch(line -> line.remainingQty(product.getId()) > 0);
        return new BarcodeLookupResponse(true, product.getId(), product.getProductName(), inThisParcel);
    }

    @Override
    public List<BoxCandidate> boxCandidates(BoxCandidateRequest request) {
        // 🔴 추천 규칙은 기억표가 소유한다(D22 ~ D25) — 여기에 두 번째 규칙을 만들지 않는다.
        return boxRecipeService.candidates(request.items().stream()
                .map(item -> new RecipeItem(item.productId(), item.quantity()))
                .toList());
    }

    // ── 박스 닫기 ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ParcelCompleteResponse complete(Long parcelId, ParcelCompleteRequest request) {
        ShipmentParcel parcel = parcel(parcelId);
        if (parcel.getStatus() == ParcelStatus.PACKED) {
            // 🔴 D15: 스캐너 Enter 가 두 번 들어와도 재고는 한 번만 빠진다.
            return completedResponse(parcel);
        }
        if (parcel.getStatus() == ParcelStatus.UNUSED) {
            throw new IllegalArgumentException("사용하지 않은 박스입니다");
        }

        List<OrderLine> lines = packingLines(parcel.getOrderShipment().getId());
        Map<Long, OrderLine> linesById = byId(lines);
        List<RemainingLine> remainingLines = stockOutService.remaining(lines);
        if (remainingLines.stream().anyMatch(RemainingLine::failed)) {
            // 무엇이 나가는지 모르는 채로 재고를 깎지 않는다. 전량 검문(D13)도 계산할 수 없다.
            throw new IllegalArgumentException("구성 물품을 전개할 수 없는 주문입니다");
        }
        Map<Long, RemainingLine> remainingById = remainingLines.stream()
                .collect(Collectors.toMap(RemainingLine::orderLineId, Function.identity()));

        int totalRemaining = totalRemainingQty(remainingLines);
        if (totalRemaining <= 0) {
            throw new IllegalArgumentException("담을 물품이 없습니다 — 사용하지 않은 박스로 닫으세요");
        }

        // 같은 (라인 × 물품) 이 여러 번 오면 합친다 — 화면은 스캔할 때마다 한 줄씩 보낼 수 있다.
        Map<Long, Map<Long, Integer>> packed = new LinkedHashMap<>();
        for (ParcelCompleteRequest.PackedItem item : request.items()) {
            packed.computeIfAbsent(item.orderLineId(), k -> new LinkedHashMap<>())
                    .merge(item.productId(), item.quantity(), Integer::sum);
        }

        int packedQty = 0;
        for (Map.Entry<Long, Map<Long, Integer>> lineEntry : packed.entrySet()) {
            RemainingLine remainingLine = remainingById.get(lineEntry.getKey());
            if (remainingLine == null) {
                throw new IllegalArgumentException("이 배송 묶음의 주문이 아닙니다");
            }
            for (Map.Entry<Long, Integer> productEntry : lineEntry.getValue().entrySet()) {
                if (productEntry.getValue() > remainingLine.remainingQty(productEntry.getKey())) {
                    throw new IllegalArgumentException("남은 수량을 초과했습니다");
                }
                packedQty += productEntry.getValue();
            }
        }

        List<ShipmentParcel> siblings =
                parcelRepository.findByOrderShipment_IdOrderByParcelSeqAsc(parcel.getOrderShipment().getId());
        if (isLastParcel(siblings, totalRemaining) && packedQty < totalRemaining) {
            // 🔴 D13: 마지막 박스가 물건이 남은 채 주문이 끝나는 것을 막는 유일한 검문소다.
            throw new IllegalArgumentException("남은 물품을 모두 담아야 합니다");
        }

        Package box = packageRepository.findById(request.boxPackageId())
                .orElseThrow(() -> new IllegalArgumentException("상자를 찾을 수 없습니다"));

        // ── 여기부터 저장. 위 검증이 하나라도 걸렸다면 아무것도 저장되지 않았다. ──

        Map<Long, Product> products = productRepository.findAllById(packed.values().stream()
                        .flatMap(byProduct -> byProduct.keySet().stream())
                        .collect(Collectors.toCollection(LinkedHashSet::new))).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        LocalDate movedOn = request.movedOn() == null ? LocalDate.now() : request.movedOn();
        List<RecipeItem> recipeItems = new ArrayList<>();
        Map<Long, Integer> recipeQtyByProduct = new LinkedHashMap<>();
        Map<Long, Integer> packedQtyByLine = new LinkedHashMap<>();
        List<String> notices = new ArrayList<>();

        for (Map.Entry<Long, Map<Long, Integer>> lineEntry : packed.entrySet()) {
            OrderLine line = linesById.get(lineEntry.getKey());
            List<OutboundConfirmRequest.ConfirmLine> confirmLines = new ArrayList<>();
            for (Map.Entry<Long, Integer> productEntry : lineEntry.getValue().entrySet()) {
                Product product = products.get(productEntry.getKey());
                if (product == null) {
                    throw new ResourceNotFoundException("Product", productEntry.getKey());
                }
                // ① 박스에 담긴 것 (항목마다 order_line_id, D2)
                parcelItemRepository.save(ShipmentParcelItem.builder()
                        .shipmentParcel(parcel)
                        .orderLine(line)
                        .product(product)
                        .quantity(productEntry.getValue())
                        .build());
                confirmLines.add(new OutboundConfirmRequest.ConfirmLine(
                        productEntry.getKey(), productEntry.getValue()));
                recipeQtyByProduct.merge(productEntry.getKey(), productEntry.getValue(), Integer::sum);
                packedQtyByLine.merge(lineEntry.getKey(), productEntry.getValue(), Integer::sum);
            }
            // ② One outbound per order line — the existing confirm path as it is (D29).
            //    A throw rolls the whole transaction back: there is no state with the box filled and the stock not
            //    taken out. FEATURE_2610_19 D49: the borrowed-goods lines of this confirm go back to the packing screen.
            stockOutService.confirm(new OutboundConfirmRequest(lineEntry.getKey(), confirmLines, movedOn))
                    .forEach(view -> notices.addAll(view.notices()));
        }
        recipeQtyByProduct.forEach((productId, qty) -> recipeItems.add(new RecipeItem(productId, qty)));

        // ③ 절약 3칸 (D4)
        Savings savings = savings(packedQtyByLine, linesById, remainingById);

        // ④ Box memory (D24). The exception is swallowed, but BoxRecipeService joins this transaction, so a failure inside it still marks the completion rollback-only.
        try {
            boxRecipeService.remember(recipeItems, box.getId());
        } catch (RuntimeException e) {
            log.warn("box recipe was not remembered for parcel {} — the stock out stands", parcel.getId(), e);
        }

        // ⑤ 박스를 닫는다 (D3)
        ShipmentParcel saved = parcelRepository.save(parcel.toBuilder()
                .status(ParcelStatus.PACKED)
                .packedAt(LocalDateTime.now())
                .packedBy(currentUsername())
                .boxPackage(box)
                .expectedBoxCost(savings.expectedBoxCost())
                .actualBoxCost(box.getCost() == null ? BigDecimal.ZERO : box.getCost())
                .expectedDeliveryCost(savings.expectedDeliveryCost())
                .build());
        // ⑥ History (FEATURE_2611_03 D16) — exceptions are swallowed, but a failed INSERT still rolls back the stock out
        //   (same transaction); D17 holds only for failures before the save.
        parcelStatusRecorder.record(saved, ParcelStatus.PENDING, ParcelStatus.PACKED, saved.getPackedBy());

        return new ParcelCompleteResponse(saved.getId(), saved.getStatus(), packedQty, notices,
                saved.getExpectedBoxCost(), saved.getActualBoxCost(), saved.getExpectedDeliveryCost());
    }

    @Override
    @Transactional
    public ParcelCloseResponse unused(Long parcelId) {
        ShipmentParcel parcel = parcel(parcelId);
        if (parcel.getStatus() == ParcelStatus.UNUSED) {
            return new ParcelCloseResponse(parcel.getId(), parcel.getStatus());
        }
        if (parcel.getStatus() == ParcelStatus.PACKED) {
            throw new IllegalArgumentException("이미 출고된 박스입니다");
        }
        // 🔴 D32: 출고도, 상자 기억도, 절약도 남기지 않는다. 누가 언제 닫았는지만 남긴다.
        ShipmentParcel saved = parcelRepository.save(parcel.toBuilder()
                .status(ParcelStatus.UNUSED)
                .packedAt(LocalDateTime.now())
                .packedBy(currentUsername())
                .build());
        parcelStatusRecorder.record(saved, ParcelStatus.PENDING, ParcelStatus.UNUSED, saved.getPackedBy());
        return new ParcelCloseResponse(saved.getId(), saved.getStatus());
    }

    @Override
    @Transactional
    public ParcelCloseResponse restore(Long parcelId) {
        ShipmentParcel parcel = parcel(parcelId);
        if (parcel.getStatus() == ParcelStatus.PENDING) {
            // FEATURE_2611_03 D11: a second Enter on the confirm dialog is not an error.
            return new ParcelCloseResponse(parcel.getId(), parcel.getStatus());
        }
        if (parcel.getStatus() == ParcelStatus.PACKED) {
            throw new IllegalArgumentException("이미 출고된 박스입니다");
        }
        // 🔴 D7: the same remaining total the complete path uses — 0 means every item already left in another box
        //    (or the order was cancelled), so a reopened box would have nothing to pack.
        if (totalRemainingQty(stockOutService.remaining(packingLines(parcel.getOrderShipment().getId()))) <= 0) {
            throw new IllegalArgumentException(
                    "담을 물품이 없어 되돌릴 수 없습니다 — 이 주문은 이미 모두 출고됐거나 취소됐습니다");
        }
        // 🔴 D3: only the three columns unused() wrote go back — stock out, recipe and savings were never written.
        ShipmentParcel saved = parcelRepository.save(parcel.toBuilder()
                .status(ParcelStatus.PENDING)
                .packedAt(null)
                .packedBy(null)
                .build());
        parcelStatusRecorder.record(saved, ParcelStatus.UNUSED, ParcelStatus.PENDING, currentUsername());
        return new ParcelCloseResponse(saved.getId(), saved.getStatus());
    }

    @Override
    @Transactional
    public ParcelCompleteResponse changeBox(Long parcelId, Long boxPackageId) {
        ShipmentParcel parcel = parcel(parcelId);
        if (parcel.getStatus() != ParcelStatus.PACKED) {
            throw new IllegalArgumentException("포장 완료한 박스만 상자를 바꿀 수 있습니다");
        }
        Package oldBox = parcel.getBoxPackage();
        if (oldBox != null && oldBox.getId().equals(boxPackageId)) {
            // D4: the same box again changes nothing — no memory move, no history row (D7).
            return completedResponse(parcel);
        }
        Package box = packageRepository.findById(boxPackageId)
                .orElseThrow(() -> new IllegalArgumentException("상자를 찾을 수 없습니다"));

        // 🔴 D4: only the box and its actual cost move — stock out, contents, packedAt, packedBy and the two
        //    expected costs stay as the complete path wrote them.
        ShipmentParcel saved = parcelRepository.save(parcel.toBuilder()
                .boxPackage(box)
                .actualBoxCost(box.getCost() == null ? BigDecimal.ZERO : box.getCost())
                .build());

        List<ShipmentParcelItem> items = parcelItemRepository.findByShipmentParcel_Id(saved.getId());
        Map<Long, Integer> recipeQtyByProduct = new LinkedHashMap<>();
        items.forEach(item -> recipeQtyByProduct.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum));
        List<RecipeItem> recipeItems = new ArrayList<>();
        recipeQtyByProduct.forEach((productId, qty) -> recipeItems.add(new RecipeItem(productId, qty)));

        // D5: the memory follows the box. Like the complete path (2609_40 D24) the exception is swallowed, but BoxRecipeService joins this transaction, so a failure inside it still marks the box change rollback-only.
        try {
            if (oldBox != null) {
                boxRecipeService.forget(recipeItems, oldBox.getId());
            }
            boxRecipeService.remember(recipeItems, box.getId());
        } catch (RuntimeException e) {
            log.warn("box recipe was not moved for parcel {} — the box change is rolled back with it (same transaction)", saved.getId(), e);
        }

        // D7 · D14: one 「상자 변경」 row (PACKED -> PACKED) by the person who changed it.
        parcelStatusRecorder.recordBoxChange(saved, currentUsername());

        int packedQty = items.stream().mapToInt(ShipmentParcelItem::getQuantity).sum();
        return new ParcelCompleteResponse(saved.getId(), saved.getStatus(), packedQty, List.of(),
                saved.getExpectedBoxCost(), saved.getActualBoxCost(), saved.getExpectedDeliveryCost());
    }

    @Override
    public List<PackingHistoryView> history(LocalDate from, LocalDate to, String keyword) {
        String trimmed = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        // D39: stored invoice numbers are normalized, so the keyword is normalized for that side only.
        String invoiceKeyword = trimmed == null ? null : InvoiceNumbers.normalize(trimmed);
        // D40: from · to are KST days; stored times are UTC wall clock, so the bounds are KST midnights in UTC.
        return statusLogRepository.search(kstMidnightAsUtc(from), kstMidnightAsUtc(to.plusDays(1)), trimmed,
                        invoiceKeyword)
                .stream()
                .map(this::historyView)
                .toList();
    }

    // ── 내부 ──────────────────────────────────────────────────────────────────

    /** 판매가 계산에 들어간 상자비·택배비 합계. 설정을 못 읽으면 둘 다 null 이다. */
    private record Savings(BigDecimal expectedBoxCost, BigDecimal expectedDeliveryCost) {
    }

    /**
     * 절약 기록의 재료 (PLAN D4).
     *
     * <pre>
     *   비율 = (이 박스에 담은 그 라인의 물품 수량) ÷ (그 라인의 전체 필요 물품 수량)
     *   expected_box_cost      += 옵션의 계산상 상자비 × orderQty × 비율
     *   expected_delivery_cost += 옵션의 계산상 택배비 × orderQty × 비율
     * </pre>
     *
     * <p>비율로 나누므로 한 라인이 두 박스에 나뉘어도 <b>두 박스의 합이 원래 값과 정확히 같다</b>.
     *
     * <p>🔴 「계산상」 값은 판매가가 쓰는 경로 그대로다({@code PriceCalculator.resolveBasis} 와 같은 것) —
     * 새 계산을 만들지 않는다. 그 resolver 는 설정이 없으면 400 을 던지는데, 여기서는 <b>감싸서 NULL 로 두고
     * 출고는 진행한다</b>: 집계가 비는 것이 출고를 막는 것보다 낫다. 미매핑 라인(옵션 null)도 같다.
     */
    private Savings savings(Map<Long, Integer> packedQtyByLine, Map<Long, OrderLine> linesById,
                            Map<Long, RemainingLine> remainingById) {
        BigDecimal expectedBox = BigDecimal.ZERO;
        BigDecimal expectedDelivery = BigDecimal.ZERO;

        for (Map.Entry<Long, Integer> entry : packedQtyByLine.entrySet()) {
            OrderLine line = linesById.get(entry.getKey());
            ProductListingOption option = line == null ? null : line.getProductListingOption();
            RemainingLine remainingLine = remainingById.get(entry.getKey());
            int required = remainingLine == null ? 0 : remainingLine.totalRequiredQty();
            if (option == null || required <= 0) {
                return new Savings(null, null);
            }
            try {
                BigDecimal ratio = BigDecimal.valueOf(entry.getValue())
                        .divide(BigDecimal.valueOf(required), 10, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(line.getOrderQty()));
                BigDecimal boxCost = masterChannelConfigService
                        .resolvePackage(option.getProductListing(), option.getMasterProductOption()).getCost();
                BigDecimal deliveryCost = masterChannelConfigService
                        .resolveDelivery(option.getProductListing(), option.getMasterProductOption()).getCost();
                expectedBox = expectedBox.add(boxCost.multiply(ratio));
                expectedDelivery = expectedDelivery.add(deliveryCost.multiply(ratio));
            } catch (RuntimeException e) {
                log.warn("packing savings left empty for order line {} — the stock out stands",
                        entry.getKey(), e);
                return new Savings(null, null);
            }
        }
        return new Savings(expectedBox.setScale(2, RoundingMode.HALF_UP),
                expectedDelivery.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * 🔴 이 배송 묶음의 <b>모든</b> 라인 — 상태로 거르지 않는다(D27). 빼는 것은 전량 취소뿐이다.
     *
     * <p>{@code effectiveStatus().isTerminal()} 로 거르면 {@code SHIPPED}(배송지시)가 함께 떨어져 나가
     * 포장 시점의 주문이 통째로 사라진다 — 그것이 이 기능을 죽이는 함정이다.
     */
    private List<OrderLine> packingLines(Long shipmentId) {
        return orderLineRepository.findByOrderShipment_Id(shipmentId).stream()
                .filter(line -> !line.isFullyCancelled())
                .toList();
    }

    /**
     * 🔴 마지막 박스인가 = 같은 배송 묶음에서 <b>작업 대상인 박스가 이것 하나뿐인가</b>(D13 · D31).
     *
     * <p>작업 대상 = {@code PENDING} + 잔량 &gt; 0. 잔량은 배송 묶음 단위라 형제 박스가 모두 같은 값을
     * 공유한다 — 그래서 잔량이 0 이면 이 묶음에는 마지막 박스라는 것이 아예 없다(백필이 만든 박스가
     * 여기 끼어 엉뚱한 곳에서 전량 검문이 걸리는 것을 막는다).
     */
    private boolean isLastParcel(Collection<ShipmentParcel> siblings, int totalRemaining) {
        if (totalRemaining <= 0) {
            return false;
        }
        return siblings.stream().filter(p -> p.getStatus() == ParcelStatus.PENDING).count() == 1;
    }

    private int totalRemainingQty(List<RemainingLine> remainingLines) {
        return remainingLines.stream()
                .filter(line -> !line.failed())
                .mapToInt(RemainingLine::totalRemainingQty)
                .sum();
    }

    /**
     * 담을 물품들을 id 로 한 번에 읽는다. 바코드(D11)와 사진(2609_54/D4)이 여기서 같이 나온다.
     *
     * <p>🔴 사진 때문에 쿼리를 새로 만들지 않는다 — 이 한 번이 이미 두 값을 다 들고 있다.
     */
    private Map<Long, Product> productsOf(List<RemainingLine> remainingLines) {
        Set<Long> productIds = remainingLines.stream()
                .filter(line -> !line.failed())
                .flatMap(line -> line.products().stream())
                .filter(p -> p.remainingQty() > 0)
                .map(com.pms.dto.response.OutboundProductLine::productId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Product> products = new LinkedHashMap<>();
        for (Product product : productRepository.findAllById(productIds)) {
            products.put(product.getId(), product);
        }
        return products;
    }

    private ShipmentParcel parcel(Long parcelId) {
        return parcelRepository.findById(parcelId)
                .orElseThrow(() -> new ResourceNotFoundException("ShipmentParcel", parcelId));
    }

    /** 이미 완료된 박스의 응답 — 저장돼 있던 값을 그대로 돌려준다(D15). */
    private ParcelCompleteResponse completedResponse(ShipmentParcel parcel) {
        int packedQty = parcelItemRepository.findByShipmentParcel_Id(parcel.getId()).stream()
                .mapToInt(ShipmentParcelItem::getQuantity)
                .sum();
        return new ParcelCompleteResponse(parcel.getId(), parcel.getStatus(), packedQty, List.of(),
                parcel.getExpectedBoxCost(), parcel.getActualBoxCost(), parcel.getExpectedDeliveryCost());
    }

    private PackingHistoryView historyView(ShipmentParcelStatusLog row) {
        ShipmentParcel parcel = row.getShipmentParcel();
        return new PackingHistoryView(row.getId(), ParcelTimes.utcToKst(row.getCreatedAt()), parcel.getId(),
                parcel.getInvoiceNumber(), parcel.getCarrierName(), parcel.getOrderShipment().getOrder().getExternalOrderId(),
                sellerNameOf(parcel), row.getFromStatus(), row.getToStatus(), row.getChangedBy(),
                parcel.getBoxPackage() == null ? null : parcel.getBoxPackage().getType(),
                parcel.getBoxPackage() == null ? null : parcel.getBoxPackage().getId());
    }

    /** KST midnight of {@code day} as a UTC wall-clock time — the clock {@code created_date} is stored in (D40). */
    private static LocalDateTime kstMidnightAsUtc(LocalDate day) {
        return day.atStartOfDay(SyncWindow.KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private PackingScanResponse.ParcelView parcelView(ShipmentParcel parcel, int totalParcels) {
        return new PackingScanResponse.ParcelView(parcel.getId(), parcel.getInvoiceNumber(),
                parcel.getCarrierName(), parcel.getParcelSeq(), totalParcels, parcel.getStatus());
    }

    private PackingScanResponse.OrderView orderView(ShipmentParcel parcel) {
        // 🔴 Order 를 새로 조회하지 않는다 — 이미 트랜잭션 안에서 타고 있는 경로다(open-in-view 가 꺼져 있다).
        Order order = parcel.getOrderShipment().getOrder();
        // 🔴 계정은 판매자와 같은 경로에서 나온다(아래 sellerNameOf) — 추가 조회 없음.
        MarketplaceAccount account = order.getMarketplaceAccount();
        return new PackingScanResponse.OrderView(order.getExternalOrderId(), sellerNameOf(parcel),
                order.getOrdererName(), order.getReceiverName(),
                account.getPlatform().name(), account.getAccountAlias());
    }

    /** 판매자는 {@code 박스 → 배송묶음 → 주문 → 계정 → 판매자} 로 유도한다(출고·재고와 같은 축). */
    private String sellerNameOf(ShipmentParcel parcel) {
        return parcel.getOrderShipment().getOrder().getMarketplaceAccount().getSeller().getSellerName();
    }

    private Map<Long, OrderLine> byId(List<OrderLine> lines) {
        return lines.stream()
                .collect(Collectors.toMap(OrderLine::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /** Same source as StockOutServiceImpl / StockLedgerServiceImpl (PLAN 2609_28 D9). */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
