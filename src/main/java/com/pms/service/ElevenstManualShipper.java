package com.pms.service;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstRawFields;
import com.pms.service.elevenst.ElevenstRejectedException;
import com.pms.service.elevenst.ElevenstSellerActions;
import com.pms.service.elevenst.ElevenstSellerActions.SplitInvoice;
import com.pms.service.stock.OrderLineExpander;
import com.pms.service.stock.OrderLineExpander.ExpandedProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The two 11st single-shipment modes besides 「한 번에 모두」 (FEATURE_2610_17 / D64): 「일부 줄만 보내기」 (1636 —
 * chosen lines of a delivery, one invoice) and 「한 줄을 송장 여러 장으로」 (6791 — one line over 2~10 invoices).
 * Called by {@code ShipmentConfirmServiceImpl.confirmManual} after it checked the line is an 11st line in preparing.
 *
 * <ul>
 *   <li>Candidates = lines of the same order ({@code ordNo}) and the same delivery ({@code dlvNo}) that are still in
 *       preparing — 1636 · 6791 carry one {@code ordNo} in the path, so a delivery's lines of another order cannot
 *       go in the same call. 「일부 줄만 보내기」 needs two or more lines in that delivery, sent ones included, so the
 *       lines left over can later go the same way.</li>
 *   <li>Success → only the sent lines become {@code SHIPPED}; every invoice is kept as a {@code shipment_parcel}, and
 *       for a split each invoice also gets its line × quantity in {@code shipment_parcel_item} (expanded to products
 *       the same way as the stock deduction — an unmapped line gets no item).</li>
 *   <li>A refusal is returned as a failed row with 11st's code and wording; nothing local changes.</li>
 * </ul>
 * ⚠️ Local writes after a success never flip the result — 11st already shipped (same stance as the 1888 path).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstManualShipper {

    static final String NO_LINE_MESSAGE = "보낼 주문 줄을 고르세요";
    static final String NOT_CANDIDATE_MESSAGE = "같은 배송건의 배송준비중 주문 줄만 고를 수 있습니다";
    static final String PARTIAL_NEEDS_TWO_MESSAGE = "일부 줄만 보내기는 주문 줄이 2개 이상인 배송건에서만 할 수 있습니다";
    static final String ADD_ON_ONLY_MESSAGE = "추가구성 상품만 따로 보낼 수 없습니다.";
    static final String SPLIT_ONE_LINE_MESSAGE = "송장을 나눌 주문 줄 1개를 고르세요";
    static final String SPLIT_QTY_LINE_MESSAGE = "수량이 2개 이상인 주문 줄만 송장을 나눌 수 있습니다";
    static final String SPLIT_COUNT_MESSAGE = "송장은 2~10장으로 나눌 수 있습니다";
    static final String SPLIT_SUM_MESSAGE = "송장별 수량의 합이 주문 수량(%d개)과 같아야 합니다.";
    private static final int MIN_SPLITS = 2;
    private static final int MAX_SPLITS = 10;

    private final OrderLineRepository orderLineRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final ElevenstSellerActions elevenstSellerActions;
    private final ShipmentParcelRecorder shipmentParcelRecorder;
    private final ShipmentParcelItemRepository shipmentParcelItemRepository;
    private final ProductRepository productRepository;
    private final OrderLineExpander orderLineExpander;

    /**
     * @param anchor        the line the order detail was opened on (an 11st line in preparing)
     * @param carrierCode   validated 11st carrier code
     * @param invoiceNumber normalized invoice of {@code PARTIAL}
     * @throws IllegalArgumentException a rule of D64 is broken (→ 400, nothing is sent)
     */
    public ManualShipmentResult ship(ManualShipmentRequest request, OrderLine anchor, MarketplaceAccount account,
                                     String carrierCode, String invoiceNumber) {
        String dlvNo = anchor.getOrderShipment().getExternalShipmentId();
        String ordNo = anchor.getOrder().getExternalOrderId();
        List<OrderLine> deliveryLines = orderLineRepository.findByExternalOrderId(ordNo).stream()
                .filter(l -> l.getOrder().getMarketplaceAccount().getId().equals(account.getId()))
                .filter(l -> l.getOrderShipment() != null && dlvNo.equals(l.getOrderShipment().getExternalShipmentId()))
                .toList();
        List<OrderLine> candidates = deliveryLines.stream()
                .filter(l -> l.getStatus() == OrderStatus.PREPARING)
                .toList();
        List<Long> ids = request.orderItemIds() == null ? List.of() : request.orderItemIds();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException(NO_LINE_MESSAGE);
        }
        Map<Long, OrderLine> byId = candidates.stream()
                .collect(Collectors.toMap(OrderLine::getId, Function.identity(), (a, b) -> a));
        if (!byId.keySet().containsAll(ids) || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(NOT_CANDIDATE_MESSAGE);
        }
        List<OrderLine> chosen = ids.stream().map(byId::get).toList();
        Map<Long, ElevenstOrderLine> mirrors = elevenstOrderLineRepository
                .findByOrderLine_IdIn(ids).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        if (mirrors.size() != ids.size()) {
            throw new IllegalArgumentException(NOT_CANDIDATE_MESSAGE);
        }
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        if (ManualShipmentRequest.MODE_SPLIT.equals(request.mode())) {
            return split(request, chosen, mirrors, account, carrierCode, dlvNo, ordNo, anchor, now);
        }
        return partial(chosen, mirrors, deliveryLines, account, carrierCode, invoiceNumber, dlvNo, ordNo, anchor, now);
    }

    /** {@code deliveryLines} = every line of the delivery, sent or not — the two-line rule counts the delivery. */
    private ManualShipmentResult partial(List<OrderLine> chosen, Map<Long, ElevenstOrderLine> mirrors,
                                         List<OrderLine> deliveryLines, MarketplaceAccount account, String carrierCode,
                                         String invoiceNumber, String dlvNo, String ordNo, OrderLine anchor,
                                         LocalDateTime now) {
        if (deliveryLines.size() < 2) {
            throw new IllegalArgumentException(PARTIAL_NEEDS_TWO_MESSAGE);
        }
        if (chosen.stream().allMatch(l -> ElevenstRawFields.yes(mirrors.get(l.getId()), "addPrdYn"))) {
            throw new IllegalArgumentException(ADD_ON_ONLY_MESSAGE);
        }
        List<String> seqs = chosen.stream().map(l -> mirrors.get(l.getId()).getOrdPrdSeq()).toList();
        try {
            elevenstSellerActions.shipPartial(account, dlvNo, ordNo, seqs, carrierCode, invoiceNumber, now);
        } catch (Exception e) {
            log.warn("11번가 부분발송 실패: dlvNo={} lines={}", dlvNo, seqs, e);
            return failure(ordNo, dlvNo, chosen.size(), e);
        }
        recordParcel(anchor, invoiceNumber, carrierCode);
        markShipped(chosen);
        log.info("11번가 부분발송: dlvNo={} ordNo={} lines={}", dlvNo, ordNo, seqs);
        return success(ordNo, dlvNo, chosen, anchor);
    }

    private ManualShipmentResult split(ManualShipmentRequest request, List<OrderLine> chosen,
                                       Map<Long, ElevenstOrderLine> mirrors, MarketplaceAccount account,
                                       String carrierCode, String dlvNo, String ordNo, OrderLine anchor,
                                       LocalDateTime now) {
        if (chosen.size() != 1) {
            throw new IllegalArgumentException(SPLIT_ONE_LINE_MESSAGE);
        }
        OrderLine line = chosen.get(0);
        if (line.getOrderQty() == null || line.getOrderQty() < 2) {
            throw new IllegalArgumentException(SPLIT_QTY_LINE_MESSAGE);
        }
        List<ManualShipmentRequest.Split> splits = request.splits() == null ? List.of() : request.splits();
        if (splits.size() < MIN_SPLITS || splits.size() > MAX_SPLITS) {
            throw new IllegalArgumentException(SPLIT_COUNT_MESSAGE);
        }
        List<SplitInvoice> invoices = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ManualShipmentRequest.Split split : splits) {
            String invoice = InvoiceNumbers.normalizeRequired(split.invoiceNumber());
            if (!seen.add(invoice)) {
                throw new IllegalArgumentException("같은 송장번호가 두 번 들어 있습니다");
            }
            invoices.add(new SplitInvoice(invoice, split.quantity()));
        }
        int sum = invoices.stream().mapToInt(SplitInvoice::quantity).sum();
        if (sum != line.getOrderQty()) {
            throw new IllegalArgumentException(String.format(SPLIT_SUM_MESSAGE, line.getOrderQty()));
        }
        String ordPrdSeq = mirrors.get(line.getId()).getOrdPrdSeq();
        try {
            elevenstSellerActions.shipSplit(account, dlvNo, ordNo, ordPrdSeq, carrierCode, invoices, now);
        } catch (Exception e) {
            log.warn("11번가 수량 나눠 발송 실패: dlvNo={} ordPrdSeq={}", dlvNo, ordPrdSeq, e);
            return failure(ordNo, dlvNo, 1, e);
        }
        for (SplitInvoice invoice : invoices) {
            recordParcel(anchor, invoice.invoiceNumber(), carrierCode)
                    .ifPresent(parcel -> recordItems(parcel, line, invoice.quantity()));
        }
        markShipped(chosen);
        log.info("11번가 수량 나눠 발송: dlvNo={} ordPrdSeq={} invoices={}", dlvNo, ordPrdSeq, invoices.size());
        return success(ordNo, dlvNo, chosen, anchor);
    }

    private Optional<ShipmentParcel> recordParcel(OrderLine anchor, String invoiceNumber, String carrierCode) {
        try {
            return shipmentParcelRecorder.record(anchor.getOrderShipment(), invoiceNumber, carrierCode, null);
        } catch (Exception e) {
            log.warn("11번가 실물 박스 저장 실패(전송은 성공): invoice={}", invoiceNumber, e);
            return Optional.empty();
        }
    }

    /** Line × quantity of one split invoice, expanded to products like the stock deduction (no option → no item). */
    private void recordItems(ShipmentParcel parcel, OrderLine line, int quantity) {
        try {
            var expansion = orderLineExpander.expand(List.of(line), l -> quantity).get(line.getId());
            if (expansion == null || expansion.failed()) {
                return;
            }
            List<ShipmentParcelItem> items = new ArrayList<>();
            for (ExpandedProduct product : expansion.products()) {
                items.add(ShipmentParcelItem.builder()
                        .shipmentParcel(parcel)
                        .orderLine(line)
                        .product(productRepository.getReferenceById(product.productId()))
                        .quantity(product.quantity())
                        .build());
            }
            shipmentParcelItemRepository.saveAll(items);
        } catch (Exception e) {
            log.warn("11번가 박스 내용물 저장 실패(전송은 성공): parcel={} line={}", parcel.getId(), line.getId(), e);
        }
    }

    private void markShipped(List<OrderLine> lines) {
        try {
            orderLineRepository.saveAll(lines.stream()
                    .map(l -> l.toBuilder().status(OrderStatus.SHIPPED).build())
                    .toList());
        } catch (Exception e) {
            log.warn("11번가 발송 로컬 상태 갱신 실패(전송은 성공): lines={}", lines.size(), e);
        }
    }

    /** The anchor's status follows only when it was among the sent lines. */
    private static ManualShipmentResult success(String ordNo, String dlvNo, List<OrderLine> sent, OrderLine anchor) {
        boolean anchorSent = sent.stream().anyMatch(l -> l.getId().equals(anchor.getId()));
        return new ManualShipmentResult(ordNo, dlvNo, "CREATE", sent.size(), 1, List.of(),
                anchorSent ? OrderStatus.SHIPPED.name() : null);
    }

    private static ManualShipmentResult failure(String ordNo, String dlvNo, int lines, Exception e) {
        FailedBox box = (e instanceof ElevenstRejectedException rejected && rejected.getResultCode() != null)
                ? new FailedBox(dlvNo, rejected.getResultCode(),
                        rejected.getResultText() == null ? "" : rejected.getResultText())
                : new FailedBox(dlvNo, "ERROR", e.getMessage());
        return new ManualShipmentResult(ordNo, dlvNo, "CREATE", lines, 0, List.of(box), null);
    }
}
