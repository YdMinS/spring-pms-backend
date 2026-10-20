package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.ShipmentInvoiceAdapter.FileOutcome;
import com.pms.service.ShipmentInvoiceAdapter.ResultFile;
import com.pms.service.reservation.InternalShipmentStageService;
import com.pms.service.reservation.ReservedShipmentExecutor;
import com.pms.service.reservation.ShipmentLocalState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.pms.service.InvoiceWriteBack.boxIdOf;
import static com.pms.service.InvoiceWriteBack.statusName;

/**
 * {@link ShipmentConfirmService} implementation — the shipment confirm leg; each marketplace registers its invoices
 * through its {@link ShipmentInvoiceAdapter} (FEATURE_2610_23 / D21 — {@link CoupangShipmentInvoiceAdapter} ·
 * {@link ElevenstShipmentInvoiceAdapter}).
 *
 * Flow (result file): parse xlsx → find each order's lines with {@code findByExternalOrderId} → orders of a
 * marketplace without an adapter are reported as unmatched (D14) → each adapter ships its own orders in the platform
 * order (Coupang → 11st); the file's orders missing from the DB go to the Coupang adapter's lookup (D25) → the
 * internal-stage orders of a marketplace that opens the internal stage are sent right after that marketplace by
 * {@code shipInternalOrders} (acknowledge → invoice, FEATURE_2609_75 / D27 · FEATURE_2610_23 / D22 · D23).
 *
 * ⚠️ Never put @Transactional on this service (D3) — it runs external HTTP.
 *
 * <p>🔴 <b>Invoice numbers are no longer thrown away</b> (FEATURE_2609_40 / PLAN D5 ①·D8·D30): the result file's
 *    invoices are kept as {@code shipment_parcel} (physical boxes). An order with N invoices (parcel count raised) gets
 *    N rows <b>only when it has one delivery bundle</b> (the file has no bundle id), and <b>only one representative
 *    invoice goes to the marketplace</b> (send behaviour unchanged).
 *
 * <p>{@code confirmManual} (single manual, PLAN 2609_11) hands the anchor line to its marketplace's adapter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentConfirmServiceImpl implements ShipmentConfirmService {

    private static final int COL_ORDER_ID = 5;    // 주문번호
    private static final int COL_INVOICE = 6;      // 운송장번호

    /** 단건 발송처리 거절 — 내부 단계(결제완료) 배송 묶음(FEATURE_2609_75 / D27, PLAN §4-4). */
    public static final String INTERNAL_STAGE_MANUAL_MESSAGE =
            "내부 단계 주문은 여기서 발송처리할 수 없습니다. 「송장」에 송장을 저장한 뒤 출고관리의 [저장된 송장으로 발송]을 누르세요";

    private final OrderLineRepository orderLineRepository;
    private final CarrierCodeService carrierCodeService;
    private final OrderAcknowledgeService orderAcknowledgeService;
    private final InternalShipmentStageService internalShipmentStageService;
    private final InvoiceWriteBack invoiceWriteBack;
    /** FEATURE_2610_23 / D23 · D24: the marketplaces whose internal-stage orders this service sends. */
    private final InternalStageMarkets internalStageMarkets;
    /** FEATURE_2610_23 / D21: one invoice sender per marketplace. */
    private final List<ShipmentInvoiceAdapter> shipmentInvoiceAdapters;

    @Override
    public ShipmentConfirmResult confirm(MultipartFile file) {
        List<UploadRow> uploadRows = parse(file);

        // orderId → invoice numbers (file order kept, deduped).
        // 🔴 An order with a raised parcel count gets N rows with different invoices in the result file — keeping
        //    only the first row as before would lose the second box's invoice (PLAN 2609_40 D8). Only the
        //    marketplace send (Coupang · 11st, FEATURE_2610_17 / D2) uses the one representative invoice.
        Map<String, List<String>> invoicesByOrderId = new LinkedHashMap<>();
        for (UploadRow row : uploadRows) {
            List<String> invoices = invoicesByOrderId.computeIfAbsent(row.orderId(), k -> new ArrayList<>());
            if (!invoices.contains(row.invoiceNumber())) {
                invoices.add(row.invoiceNumber());      // 같은 송장의 N행(상품 여러 개)은 1장으로 접는다
            }
        }

        List<String> unmatched = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        // Orders with no line in the DB — only the Coupang adapter looks them up (FEATURE_2610_23 / D25); an order
        // of another marketplace is never looked up at Coupang, it stays unmatched.
        List<String> missingOrderIds = new ArrayList<>();
        // 택배사 코드는 플랫폼당 한 번만 읽는다(박스 저장용 — 없어도 송장은 저장된다, PLAN 2609_40 D6).
        Map<Platform, String> carrierCodeCache = new EnumMap<>(Platform.class);
        int matchedOrders = 0;
        // FEATURE_2610_23 / D21: each marketplace's orders in file order — EnumMap keeps the platform order.
        Map<Platform, Map<String, List<OrderLine>>> ordersByPlatform = new EnumMap<>(Platform.class);
        // FEATURE_2609_75 / D27 — 내부 단계 주문은 발주처리 → 송장 등록 순으로 따로 보낸다(아래 shipInternalOrders).
        Map<Platform, Map<String, List<OrderLine>>> internalByPlatform = new EnumMap<>(Platform.class);

        for (String orderId : invoicesByOrderId.keySet()) {
            List<OrderLine> lines = orderLineRepository.findByExternalOrderId(orderId);
            if (lines.isEmpty()) {
                missingOrderIds.add(orderId);
                continue;
            }
            // One order = one account → its lines share the account. A marketplace without an adapter is reported
            // as unmatched (no resolve / post — FEATURE_2610_23 / D14).
            Platform platform = lines.get(0).getOrder().getMarketplaceAccount().getPlatform();
            if (adapterFor(platform).isEmpty()) {
                unmatched.add(orderId);
                continue;
            }
            if (internalStageMarkets.supports(platform) && InternalStageMarkets.hasInternalStage(lines)) {
                // D27 — the physical box is recorded only after the invoice registration succeeds (D22).
                internalByPlatform.computeIfAbsent(platform, k -> new LinkedHashMap<>()).put(orderId, lines);
                continue;
            }
            ordersByPlatform.computeIfAbsent(platform, k -> new LinkedHashMap<>()).put(orderId, lines);
        }

        ResultFile resultFile = new ResultFile(invoicesByOrderId, missingOrderIds, carrierCodeCache);
        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            Optional<ShipmentInvoiceAdapter> adapter = adapterFor(platform);
            if (adapter.isEmpty()) {
                continue;
            }
            FileOutcome outcome = adapter.get().shipFromFile(resultFile,
                    ordersByPlatform.getOrDefault(platform, Map.of()));
            matchedOrders += outcome.matchedOrders();
            succeeded += outcome.succeeded();
            failed.addAll(outcome.failed());
            skipped.addAll(outcome.skipped());
            unmatched.addAll(outcome.unmatched());

            Map<String, List<OrderLine>> internalOrders = internalByPlatform.getOrDefault(platform, Map.of());
            if (!internalOrders.isEmpty()) {
                InternalShipOutcome internal = shipInternalFromFile(platform, internalOrders, invoicesByOrderId);
                matchedOrders += internalOrders.size();
                succeeded += internal.succeeded();
                failed.addAll(internal.failed());
                skipped.addAll(internal.skipped());
            }
        }

        log.info("발송처리 결과: rows={} matched={} skipped={} unmatched={} succeeded={} failed={}",
                uploadRows.size(), matchedOrders, skipped.size(), unmatched.size(), succeeded, failed.size());
        if (!skipped.isEmpty()) {
            // 정상 동작이라 info — 실패/미매칭(warn)과 구분한다.
            log.info("발송처리 스킵(이미 발송됨) {}건: {}", skipped.size(), skipped);
        }
        if (!unmatched.isEmpty()) {
            log.warn("발송처리 미매칭 주문번호 {}건: {}", unmatched.size(), unmatched);
        }
        for (FailedBox f : failed) {
            log.warn("발송처리 박스 실패: boxId={} code={} msg={}", f.shipmentBoxId(), f.resultCode(), f.message());
        }

        return new ShipmentConfirmResult(uploadRows.size(), matchedOrders, unmatched, succeeded, failed, skipped);
    }

    @Override
    public ManualShipmentResult confirmManual(ManualShipmentRequest request) {
        // 하이픈·공백 제거(쿠팡이 거부한다) — 저장·전송이 같은 값이 되도록 진입부에서 한 번만 한다.
        String invoiceNumber = InvoiceNumbers.normalizeRequired(request.invoiceNumber());   // D15
        // 1) 앵커 라인 — account 를 eager 로 읽는 finder 만 사용(open-in-view=false)
        OrderLine anchor = orderLineRepository.findWithAccountAndSellerById(request.orderItemId())
                .orElseThrow(() -> new IllegalArgumentException("주문 라인을 찾을 수 없습니다"));
        MarketplaceAccount account = anchor.getOrder().getMarketplaceAccount();
        ShipmentInvoiceAdapter adapter = adapterFor(account.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException("쿠팡 · 11번가 주문만 발송처리할 수 있습니다"));     // D7 · FEATURE_2610_17 / D2
        return adapter.shipManual(request, anchor, account, invoiceNumber);
    }

    @Override
    public Map<String, List<String>> readInvoicesByOrderId(MultipartFile file) {
        // confirm() 첫 블록과 같은 규칙 — 같은 송장의 N행은 1장으로 접고 파일 순서를 지킨다(2609_40 D8).
        Map<String, List<String>> invoicesByOrderId = new LinkedHashMap<>();
        for (UploadRow row : parse(file)) {
            List<String> invoices = invoicesByOrderId.computeIfAbsent(row.orderId(), k -> new ArrayList<>());
            if (!invoices.contains(row.invoiceNumber())) {
                invoices.add(row.invoiceNumber());
            }
        }
        return invoicesByOrderId;
    }

    @Override
    public ReservedInvoiceResult sendReservedInvoices(List<ReservedInvoice> invoices) {
        List<Long> succeeded = new ArrayList<>();
        Map<Long, String> failed = new LinkedHashMap<>();
        if (invoices.isEmpty()) {
            return new ReservedInvoiceResult(succeeded, failed);
        }
        Map<Long, ReservedInvoice> byShipmentId = new LinkedHashMap<>();
        invoices.forEach(i -> byShipmentId.putIfAbsent(i.orderShipmentId(), i));
        Map<Long, List<OrderLine>> linesByShipment = new LinkedHashMap<>();
        for (OrderLine line : orderLineRepository.findWithAccountByOrderShipment_IdIn(byShipmentId.keySet())) {
            linesByShipment.computeIfAbsent(line.getOrderShipment().getId(), k -> new ArrayList<>()).add(line);
        }

        // FEATURE_2610_23 / D23 · D24: each marketplace sends its own bundles through its adapter's internal-stage
        // slot, in the platform order.
        Map<Platform, List<ReservedInvoice>> invoicesByPlatform = new EnumMap<>(Platform.class);
        for (Long shipmentId : byShipmentId.keySet()) {
            List<OrderLine> lines = linesByShipment.get(shipmentId);
            if (lines == null || lines.isEmpty()) {
                failed.put(shipmentId, "주문 라인을 찾을 수 없습니다");
                continue;
            }
            invoicesByPlatform.computeIfAbsent(platformOf(lines), k -> new ArrayList<>())
                    .add(byShipmentId.get(shipmentId));
        }
        invoicesByPlatform.forEach((platform, platformInvoices) -> {
            ReservedInvoiceResult sent = adapterFor(platform)
                    .orElseThrow(() -> new IllegalStateException("내부 단계가 없는 마켓의 배송 묶음입니다: " + platform))
                    .sendReservedInvoices(platformInvoices, linesByShipment);
            succeeded.addAll(sent.succeededShipmentIds());
            failed.putAll(sent.failedReasons());
        });

        // 🔴 실물 박스는 송장 등록이 성공한 뒤에 만든다(D22) — 저장된 택배사 코드로(D27), 규칙은 recordParcels 그대로(2609_40 D30 다중 묶음 가드 포함).
        Set<String> recordedOrders = new HashSet<>();
        for (Long shipmentId : succeeded) {
            ReservedInvoice reserved = byShipmentId.get(shipmentId);
            if (!recordedOrders.add(reserved.externalOrderId())) {
                continue;
            }
            Map<Platform, String> carrierCodeCache = new EnumMap<>(Platform.class);
            carrierCodeCache.put(platformOf(linesByShipment.get(shipmentId)), reserved.carrierCode());
            invoiceWriteBack.recordParcels(reserved.externalOrderId(),
                    orderLineRepository.findByExternalOrderId(reserved.externalOrderId()),
                    reserved.invoiceNumbers(), carrierCodeCache);
        }
        log.info("예약 송장 등록: shipments={} succeeded={} failed={}", byShipmentId.size(), succeeded.size(), failed.size());
        return new ReservedInvoiceResult(succeeded, failed);
    }

    @Override
    public ShipmentConfirmResult shipStoredInvoices(List<ReservedInvoice> invoices) {
        Map<Long, ReservedInvoice> invoiceByShipmentId = new LinkedHashMap<>();
        invoices.forEach(i -> invoiceByShipmentId.putIfAbsent(i.orderShipmentId(), i));
        Map<String, List<OrderLine>> internalOrders = new LinkedHashMap<>();
        if (!invoiceByShipmentId.isEmpty()) {
            for (OrderLine line : orderLineRepository.findWithAccountByOrderShipment_IdIn(invoiceByShipmentId.keySet())) {
                internalOrders.computeIfAbsent(line.getOrder().getExternalOrderId(), k -> new ArrayList<>()).add(line);
            }
        }
        InternalShipOutcome outcome = internalOrders.isEmpty()
                ? new InternalShipOutcome(0, List.of(), List.of())
                : shipInternalOrders(internalOrders, invoiceByShipmentId);
        log.info("[지금 발송] 저장된 송장: orders={} succeeded={} failed={} skipped={}",
                internalOrders.size(), outcome.succeeded(), outcome.failed().size(), outcome.skipped().size());
        return new ShipmentConfirmResult(internalOrders.size(), internalOrders.size(), List.of(),
                outcome.succeeded(), outcome.failed(), outcome.skipped());
    }

    /**
     * The internal-stage orders of a [지금 발송] result file (D27 · FEATURE_2610_23 / D22) — carrier = the
     * marketplace's code of the active carrier (the same as the bulk shipment confirm), invoice = the file. Without a
     * carrier code nothing is acknowledged either — an order is never left acknowledged while its invoice cannot go.
     */
    private InternalShipOutcome shipInternalFromFile(Platform platform, Map<String, List<OrderLine>> internalOrders,
                                                     Map<String, List<String>> invoicesByOrderId) {
        String carrierCode;
        try {
            carrierCode = carrierCodeService.resolveDeliveryCompanyCode(platform);
        } catch (Exception e) {
            List<FailedBox> failed = new ArrayList<>();
            internalOrders.values().forEach(lines -> lines.stream().map(InvoiceWriteBack::boxIdOf)
                    .filter(Objects::nonNull)
                    .distinct().forEach(boxId -> failed.add(new FailedBox(boxId, "ERROR", e.getMessage()))));
            return new InternalShipOutcome(0, failed, List.of());
        }
        Map<Long, ReservedInvoice> invoiceByShipmentId = new LinkedHashMap<>();
        internalOrders.forEach((orderId, lines) -> lines.stream()
                .filter(l -> l.getOrderShipment() != null && l.getOrderShipment().getId() != null)
                .forEach(l -> invoiceByShipmentId.putIfAbsent(l.getOrderShipment().getId(),
                        new ReservedInvoice(l.getOrderShipment().getId(), orderId, carrierCode,
                                invoicesByOrderId.get(orderId)))));
        return shipInternalOrders(internalOrders, invoiceByShipmentId);
    }

    /**
     * [지금 발송] 중 「내부 상품준비중」·「발송대기중」 묶음이 있는 주문 (FEATURE_2609_75 / D27 · D18).
     * 호출자 = {@link #shipInternalFromFile}(결과 파일) · {@link #shipStoredInvoices}(저장된 송장).
     *
     * <p>예약 실행과 같은 순서다 — ② 발주처리({@link OrderAcknowledgeService#acknowledgeForReservation}) →
     * ③ 송장 등록({@link #sendReservedInvoices} — {@code shipment_parcel} 은 성공 뒤 기록, D22). 기다림만 없다.
     * 보내는 묶음 = {@code invoiceByShipmentId} 에 송장이 있는 묶음뿐이다(발주처리도 그 묶음의 PAID 라인만).
     * ②·③ 직전마다 {@link #sendableNow} 로 다시 판정해 상태가 바뀐 묶음은 요청에서 뺀다(D27 × D16 — ③ 직전은 DB 를 다시 읽는다).
     * 발주처리에 실패한 박스에는 송장을 보내지 않는다. 송장 등록이 성공한 묶음만 내부 단계를 지우고 남은 예약 결과·보관 송장을
     * 해제한다(실행기가 성공 묶음만 지우는 것과 같다). 예약이 실행 중(RUNNING)인 주문은 보내지 않고 실패(코드 RUNNING)로 알린다(D18).
     * ⚠️ 여기서 던지면 안 된다 — 같은 요청의 다른 주문 결과가 사라진다. 실패는 전부 {@code FailedBox} 로 돌려준다.
     */
    private InternalShipOutcome shipInternalOrders(Map<String, List<OrderLine>> internalOrders,
                                                   Map<Long, ReservedInvoice> invoiceByShipmentId) {
        List<FailedBox> failed = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        List<Long> partial = new ArrayList<>();
        Map<Long, OrderLine> anchorByShipmentId = new LinkedHashMap<>();
        List<Long> ackLineIds = new ArrayList<>();
        Set<String> ackBoxIds = new LinkedHashSet<>();
        for (Map.Entry<String, List<OrderLine>> order : internalOrders.entrySet()) {
            Map<Long, List<OrderLine>> linesByShipment = new LinkedHashMap<>();
            for (OrderLine line : order.getValue()) {
                if (line.getOrderShipment() != null && invoiceByShipmentId.containsKey(line.getOrderShipment().getId())) {
                    linesByShipment.computeIfAbsent(line.getOrderShipment().getId(), k -> new ArrayList<>()).add(line);
                }
            }
            try {
                internalShipmentStageService.assertNotRunning(linesByShipment.keySet());
            } catch (IllegalArgumentException e) {
                linesByShipment.values().forEach(l -> failed.add(new FailedBox(boxIdOf(l.get(0)), "RUNNING", e.getMessage())));
                continue;
            }
            // ② 직전 판정 — 상태가 바뀐 묶음은 발주처리·송장 등록 모두에서 뺀다.
            for (Map.Entry<Long, List<OrderLine>> shipment : linesByShipment.entrySet()) {
                if (!sendableNow(order.getKey(), shipment.getValue(), failed, skipped, partial)) {
                    continue;
                }
                anchorByShipmentId.put(shipment.getKey(), shipment.getValue().get(0));
                for (OrderLine line : shipment.getValue()) {
                    if (line.getStatus() == OrderStatus.PAID && line.getOrderShipment().getInternalStage() != null) {
                        ackLineIds.add(line.getId());
                        ackBoxIds.add(boxIdOf(line));
                    }
                }
            }
        }

        Set<String> ackFailedBoxIds = new HashSet<>();
        if (!ackLineIds.isEmpty()) {
            try {
                ReservationAckResult ack = orderAcknowledgeService.acknowledgeForReservation(ackLineIds);
                for (FailedBox box : ack.failed()) {
                    ackFailedBoxIds.add(box.shipmentBoxId());
                    failed.add(new FailedBox(box.shipmentBoxId(), box.resultCode(), "발주처리 실패: " + box.message()));
                }
                for (String boxId : ackBoxIds) {
                    if (!ack.succeededBoxIds().contains(boxId) && ackFailedBoxIds.add(boxId)) {
                        failed.add(new FailedBox(boxId, "ERROR", "발주처리 결과를 받지 못했습니다"));
                    }
                }
            } catch (Exception e) {
                log.warn("[지금 발송] 발주처리 실패: lines={}", ackLineIds.size(), e);
                for (String boxId : ackBoxIds) {
                    ackFailedBoxIds.add(boxId);
                    failed.add(new FailedBox(boxId, "ERROR", "발주처리 실패: " + e.getMessage()));
                }
            }
        }

        Map<Long, OrderLine> toInvoice = new LinkedHashMap<>();
        anchorByShipmentId.forEach((shipmentId, anchor) -> {
            if (!ackFailedBoxIds.contains(boxIdOf(anchor))) {
                toInvoice.put(shipmentId, anchor);         // 발주처리 실패 묶음은 송장을 보내지 않는다
            }
        });
        // ③ 직전 판정 — ② 가 로컬 상태를 바꿨으므로 DB 를 다시 읽는다.
        Map<Long, List<OrderLine>> fresh = new LinkedHashMap<>();
        if (!toInvoice.isEmpty()) {
            for (OrderLine line : orderLineRepository.findWithAccountByOrderShipment_IdIn(toInvoice.keySet())) {
                fresh.computeIfAbsent(line.getOrderShipment().getId(), k -> new ArrayList<>()).add(line);
            }
        }
        List<ReservedInvoice> invoices = new ArrayList<>();
        Map<Long, String> boxIdByShipmentId = new HashMap<>();
        for (Map.Entry<Long, OrderLine> entry : toInvoice.entrySet()) {
            ReservedInvoice invoice = invoiceByShipmentId.get(entry.getKey());
            List<OrderLine> lines = fresh.getOrDefault(entry.getKey(), List.of());
            if (lines.isEmpty()) {
                failed.add(new FailedBox(boxIdOf(entry.getValue()), "ERROR", "주문 라인을 찾을 수 없습니다"));
                continue;
            }
            if (!sendableNow(invoice.externalOrderId(), lines, failed, skipped, partial)) {
                continue;
            }
            boxIdByShipmentId.put(entry.getKey(), boxIdOf(entry.getValue()));
            invoices.add(invoice);
        }
        ReservedInvoiceResult sent = sendReservedInvoices(invoices);
        sent.failedReasons().forEach((shipmentId, reason) ->
                failed.add(new FailedBox(boxIdByShipmentId.get(shipmentId), "ERROR", reason)));
        if (!sent.succeededShipmentIds().isEmpty()) {
            try {
                internalShipmentStageService.clearAfterShipNow(sent.succeededShipmentIds());
            } catch (Exception e) {
                log.warn("[지금 발송] 내부 단계·예약 해제 실패 (쿠팡 송장 등록은 성공): {}", e.getMessage());
            }
        }
        if (!partial.isEmpty()) {
            try {
                internalShipmentStageService.releasePartialCancel(partial);
            } catch (Exception e) {
                log.warn("[지금 발송] 일부 취소 묶음 해제 실패: shipments={} {}", partial, e.getMessage());
            }
        }
        log.info("[지금 발송] 내부 단계 주문: orders={} acknowledged={} invoiced={} failed={} skipped={} partial={}",
                internalOrders.size(), ackLineIds.size(), sent.succeededShipmentIds().size(), failed.size(),
                skipped.size(), partial.size());
        return new InternalShipOutcome(sent.succeededShipmentIds().size(), failed, skipped);
    }

    /**
     * [지금 발송] ②·③ 직전 판정 (FEATURE_2609_75 / D27 × D16) — 규칙은 {@link ShipmentLocalState#judge} 하나다. 보낼 묶음이면 true.
     * 전량 취소·발송 이후 = {@code skipped} · 일부 수량 취소 = {@code partial}(뒤에서 해제) + {@code failed}(PARTIAL_CANCEL) ·
     * 판정 불가 = {@code failed}. 쿠팡을 부르지 않는다.
     */
    private boolean sendableNow(String orderId, List<OrderLine> lines, List<FailedBox> failed,
                                List<SkippedOrder> skipped, List<Long> partial) {
        OrderLine first = lines.get(0);
        switch (ShipmentLocalState.judge(lines)) {
            case PAID, PREPARING -> {
                return true;
            }
            case CANCELLED -> skipped.add(new SkippedOrder(orderId, OrderStatus.CANCELLED.name(),
                    ShipmentConfirmResult.ALREADY_SHIPPED_REASON));
            case EXTERNAL -> skipped.add(new SkippedOrder(orderId, statusName(first),
                    ShipmentConfirmResult.ALREADY_SHIPPED_REASON));
            case PARTIALLY_CANCELLED -> {
                partial.add(first.getOrderShipment().getId());
                failed.add(new FailedBox(boxIdOf(first), "PARTIAL_CANCEL", ReservedShipmentExecutor.PARTIAL_CANCEL_MESSAGE));
            }
            default -> failed.add(new FailedBox(boxIdOf(first), "ERROR", "주문 상태를 확인할 수 없습니다"));
        }
        return false;
    }

    /** {@link #shipInternalOrders} 결과 — 송장 등록에 성공한 배송 묶음 수 · 실패 박스 · 상태가 바뀌어 뺀 주문(D27 × D16). */
    private record InternalShipOutcome(int succeeded, List<FailedBox> failed, List<SkippedOrder> skipped) {
    }

    /** The adapter whose platform matches — same lookup as {@code ClaimActionServiceImpl.resolve()}. */
    private Optional<ShipmentInvoiceAdapter> adapterFor(Platform platform) {
        return shipmentInvoiceAdapters.stream().filter(adapter -> adapter.platform() == platform).findFirst();
    }

    /** The marketplace of a bundle's lines (one bundle = one account). */
    private static Platform platformOf(List<OrderLine> lines) {
        return lines.get(0).getOrder().getMarketplaceAccount().getPlatform();
    }

    /** 택배사 결과 xlsx 파싱 → 주문번호(5)·운송장번호(6)만 사용, 공백행 스킵. */
    private List<UploadRow> parse(MultipartFile file) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<UploadRow> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String orderId = cellString(row.getCell(COL_ORDER_ID), formatter);
                // 결과 파일의 송장에도 하이픈이 섞여 올 수 있다 — 전송·저장·중복 제거가 같은 값을 보게 한다.
                String invoiceNumber = InvoiceNumbers.normalize(cellString(row.getCell(COL_INVOICE), formatter));
                if (orderId.isBlank() || invoiceNumber.isBlank()) {
                    continue;                       // 주문번호/운송장번호 중 하나라도 공백이면 스킵
                }
                rows.add(new UploadRow(orderId, invoiceNumber));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalArgumentException("발송처리 파일 파싱 실패", e);
        }
    }

    /** 셀을 문자열로 읽되 숫자셀은 지수표기로 깨지지 않게 long 으로 읽는다. */
    private String cellString(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return String.valueOf((long) cell.getNumericCellValue());
        }
        return formatter.formatCellValue(cell).trim();
    }

    /** 업로드 xlsx 한 행에서 사용하는 두 열. */
    private record UploadRow(String orderId, String invoiceNumber) {
    }
}
