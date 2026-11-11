package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.dto.request.ManualShipmentRequest.RecordChoice;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ManualShipmentResult.RecordOutcome;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderApi.LineFailure;
import com.pms.service.lotteon.LotteonOrderRows;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LotteON invoice registration — [298] delivery line step 13 (FEATURE_2611_23 / D18) — and the invoice update of an
 * already shipped order — [139] per chosen record (D19).
 *
 * <ul>
 *   <li>Result file · single invoice · reserved invoices all send the same [298] line: {@code odNo} · {@code odSeq} ·
 *       {@code procSeq} of the mirror, step {@code 13}, the call time, one representative invoice and
 *       {@code slQty} = order − cancel quantity. At most {@value #CHUNK} lines per call, per sales channel (the
 *       reserved run = one call per bundle). A fully cancelled line is not sent and not counted.</li>
 *   <li>A line LotteON lists in {@code failList} fails with LotteON's code and text (D15 · D44); the accepted lines
 *       become {@code SHIPPED} locally ({@link InvoiceWriteBack#markDeparted}, key = {@code odNo|odSeq}).</li>
 *   <li>Invoice update (D19): the [140] states of the order are read once and every chosen record is sent on its own —
 *       [139] with the record's current invoice, or [298] once with the record's {@code procSeq} when the record has
 *       no invoice yet (⑤). A record not found or not in step 13 · 14 · 15 is not sent (R15 · R20). The order status
 *       is not changed.</li>
 * </ul>
 * The internal-stage slot {@link #sendReservedInvoices} is filled, so {@link LotteonOrderAcknowledgeAdapter} opens
 * the internal stage (D49 ① ③).
 *
 * <p>⚠️ {@link #shipFromFile} never throws — every failure is a {@link FailedBox}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteonShipmentInvoiceAdapter implements ShipmentInvoiceAdapter {

    /** D18: a LotteON paid order needs [발주처리] before its invoice (single invoice → 400). */
    public static final String LOTTEON_ACKNOWLEDGE_FIRST_MESSAGE = "롯데ON 주문은 [발주처리] 뒤에 송장을 등록할 수 있습니다.";
    /** D18: the result file skips a LotteON paid order with this reason. */
    public static final String LOTTEON_ACKNOWLEDGE_FIRST_REASON = "롯데ON에서 발주처리를 먼저 해 주세요.";
    /** Same wording as the Coupang · 11st paths when a line has no delivery bundle. */
    static final String NO_BOX_MESSAGE = "박스 ID가 없습니다. 주문동기화 후 다시 시도하세요";
    /** R15 · R20: the chosen record is not in the re-read [140] states (or cannot be modified). */
    static final String NO_RECORD_MESSAGE = "롯데ON 에 발송 기록이 없어 고칠 수 없습니다.";
    /** D18: at most this many lines per [298] call. */
    static final int CHUNK = 500;

    private static final String NO_MIRROR_MESSAGE = "롯데ON 주문 줄 정보가 없습니다";
    private static final String MODE_CREATE = "CREATE";
    private static final String MODE_UPDATE = "UPDATE";
    private static final String ERROR = "ERROR";
    private static final String STEP_SHIPPED = "13";
    /** D19 ⑥-10: the steps whose record can be modified. */
    private static final Set<String> MODIFIABLE_STEPS = Set.of("13", "14", "15");

    private final OrderLineRepository orderLineRepository;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;
    private final CarrierCodeService carrierCodeService;
    private final ShipmentParcelRecorder shipmentParcelRecorder;
    private final LotteonOrderApi lotteonOrderApi;
    private final InvoiceWriteBack invoiceWriteBack;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /**
     * LotteON orders of a result file (D18 · D19). Send target = {@code PREPARING} lines; an order with none is
     * skipped — paid → {@link #LOTTEON_ACKNOWLEDGE_FIRST_REASON}, otherwise 「이미 발송처리된 상태입니다.」. Carrier =
     * the active carrier's LotteON code, read only when a line is to be sent; missing → every bundle to send fails.
     */
    @Override
    public FileOutcome shipFromFile(ResultFile file, Map<String, List<OrderLine>> orders) {
        Map<String, List<String>> invoicesByOrderId = file.invoicesByOrderId();
        List<FailedBox> failed = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        List<OrderLine> toSend = new ArrayList<>();
        Map<Long, String> invoiceByLineId = new LinkedHashMap<>();
        int matched = 0;
        for (Map.Entry<String, List<OrderLine>> order : orders.entrySet()) {
            String orderId = order.getKey();
            List<OrderLine> lines = order.getValue();
            invoiceWriteBack.recordParcels(orderId, lines, invoicesByOrderId.get(orderId), file.carrierCodeCache());
            List<OrderLine> preparing = lines.stream().filter(l -> l.getStatus() == OrderStatus.PREPARING).toList();
            if (preparing.isEmpty()) {
                boolean paid = lines.stream().anyMatch(l -> l.getStatus() == OrderStatus.PAID);
                skipped.add(new SkippedOrder(orderId, InvoiceWriteBack.statusName(lines.get(0)), paid
                        ? LOTTEON_ACKNOWLEDGE_FIRST_REASON
                        : ShipmentConfirmResult.ALREADY_SHIPPED_REASON));
                continue;
            }
            matched++;
            String invoice = InvoiceWriteBack.representativeInvoice(invoicesByOrderId, orderId);
            for (OrderLine line : preparing) {
                String boxId = InvoiceWriteBack.boxIdOf(line);
                if (boxId == null || boxId.isBlank()) {
                    failed.add(new FailedBox("", ERROR, NO_BOX_MESSAGE));
                    continue;
                }
                if (line.isFullyCancelled()) {
                    continue;
                }
                toSend.add(line);
                invoiceByLineId.put(line.getId(), invoice);
            }
        }
        if (toSend.isEmpty()) {
            return new FileOutcome(matched, 0, failed, skipped, List.of());
        }
        String carrierCode;
        try {
            carrierCode = carrierCodeService.resolveDeliveryCompanyCode(Platform.LOTTEON);
        } catch (Exception e) {
            toSend.stream().map(InvoiceWriteBack::boxIdOf).collect(Collectors.toCollection(LinkedHashSet::new))
                    .forEach(boxId -> failed.add(new FailedBox(boxId, ERROR, e.getMessage())));
            return new FileOutcome(matched, 0, failed, skipped, List.of());
        }
        SendOutcome outcome;
        try {
            outcome = sendByAccount(toSend, line -> invoiceByLineId.get(line.getId()), carrierCode);
        } catch (Exception e) {
            log.warn("롯데ON 발송처리 준비 실패: lines={}", toSend.size(), e);
            toSend.stream().map(InvoiceWriteBack::boxIdOf).collect(Collectors.toCollection(LinkedHashSet::new))
                    .forEach(boxId -> failed.add(new FailedBox(boxId, ERROR, e.getMessage())));
            return new FileOutcome(matched, 0, failed, skipped, List.of());
        }
        failed.addAll(outcome.failed());
        log.info("롯데ON 발송처리: orders={} matched={} lines={} succeeded={} failed={} skipped={}",
                orders.size(), matched, outcome.sent(), outcome.accepted(), failed.size(), skipped.size());
        return new FileOutcome(matched, outcome.accepted(), failed, skipped, List.of());
    }

    /**
     * Reserved invoices (D49 ③ · R10): one [298] call per bundle with all its lines, the reserved carrier code and
     * the first invoice. The bundle succeeds only when none of its lines failed; otherwise its reason = the first
     * failure text. A bundle without a delivery bundle id fails with {@link #NO_BOX_MESSAGE}.
     */
    @Override
    public ReservedInvoiceResult sendReservedInvoices(List<ReservedInvoice> invoices,
                                                      Map<Long, List<OrderLine>> linesByShipment) {
        List<Long> succeeded = new ArrayList<>();
        Map<Long, String> failed = new LinkedHashMap<>();
        Map<Long, ReservedInvoice> byShipmentId = new LinkedHashMap<>();
        invoices.forEach(i -> byShipmentId.putIfAbsent(i.orderShipmentId(), i));
        List<OrderLine> allLines = byShipmentId.keySet().stream()
                .flatMap(id -> linesByShipment.getOrDefault(id, List.of()).stream())
                .toList();
        Map<Long, LotteonOrderLine> mirrors = mirrorsOf(allLines);
        for (Map.Entry<Long, ReservedInvoice> entry : byShipmentId.entrySet()) {
            Long shipmentId = entry.getKey();
            ReservedInvoice reserved = entry.getValue();
            List<OrderLine> lines = linesByShipment.getOrDefault(shipmentId, List.of());
            String boxId = lines.isEmpty() ? null : InvoiceWriteBack.boxIdOf(lines.get(0));
            if (boxId == null || boxId.isBlank()) {
                failed.put(shipmentId, NO_BOX_MESSAGE);
                continue;
            }
            MarketplaceAccount account = lines.get(0).getOrder().getMarketplaceAccount();
            String invoice = reserved.invoiceNumbers().get(0);
            List<FailedBox> lineFailures = new ArrayList<>();
            List<LineSend> sends = prepare(lines, mirrors, line -> invoice, lineFailures);
            lineFailures.addAll(sendChunk(account, sends, reserved.carrierCode()).failed());
            if (lineFailures.isEmpty()) {
                succeeded.add(shipmentId);
            } else {
                log.warn("롯데ON 예약 송장 발송처리 실패: shipment={} box={} lines={}", shipmentId, boxId,
                        lineFailures.size());
                failed.put(shipmentId, lineFailures.get(0).message());
            }
        }
        return new ReservedInvoiceResult(succeeded, failed);
    }

    /**
     * Single LotteON invoice (R13): no bundle → 400 · paid → 400 (D18) · carrier check · shipped or later → invoice
     * update of the chosen records (D19) · otherwise every {@code PREPARING} line of the bundle with one [298] send.
     * {@code request.mode()} is not read (LotteON = the whole bundle at once, D18).
     */
    @Override
    public ManualShipmentResult shipManual(ManualShipmentRequest request, OrderLine anchor,
                                           MarketplaceAccount account, String invoiceNumber) {
        String boxId = InvoiceWriteBack.boxIdOf(anchor);
        if (boxId == null || boxId.isBlank()) {
            throw new IllegalArgumentException(NO_BOX_MESSAGE);
        }
        if (anchor.getStatus() == OrderStatus.PAID) {
            throw new IllegalArgumentException(LOTTEON_ACKNOWLEDGE_FIRST_MESSAGE);
        }
        String carrierCode = carrierCodeService.validateDeliveryCompanyCode(request.deliveryCompanyCode(),
                Platform.LOTTEON);
        String externalOrderId = anchor.getOrder().getExternalOrderId();
        if (anchor.getStatus() != null && anchor.getStatus().isTerminal()) {
            return modify(request.records(), anchor, account, boxId, carrierCode, invoiceNumber);
        }
        List<OrderLine> lines = bundleLines(anchor, account, boxId);
        recordParcel(anchor, invoiceNumber, carrierCode);
        List<OrderLine> preparing = lines.stream().filter(l -> l.getStatus() == OrderStatus.PREPARING).toList();
        SendOutcome outcome = sendByAccount(preparing, line -> invoiceNumber, carrierCode);
        return new ManualShipmentResult(externalOrderId, boxId, MODE_CREATE, outcome.sent(), outcome.accepted(),
                outcome.failed(), outcome.failed().isEmpty() ? InvoiceWriteBack.STATUS_SHIPPED.name() : null);
    }

    /**
     * D19 invoice update: nothing chosen → nothing sent (R14). Otherwise the physical box once, [140] of the order
     * once, then every chosen record in request order — one failure does not stop the next (⑥-6).
     */
    private ManualShipmentResult modify(List<RecordChoice> choices, OrderLine anchor, MarketplaceAccount account,
                                        String boxId, String carrierCode, String invoiceNumber) {
        String externalOrderId = anchor.getOrder().getExternalOrderId();
        if (choices == null || choices.isEmpty()) {
            return new ManualShipmentResult(externalOrderId, boxId, MODE_UPDATE, 0, 0, List.of(), null, List.of());
        }
        recordParcel(anchor, invoiceNumber, carrierCode);
        List<JsonNode> states = lotteonOrderApi.progressStatesOfOrder(account, externalOrderId);
        Map<Long, OrderLine> bundle = new LinkedHashMap<>();
        bundleLines(anchor, account, boxId).forEach(l -> bundle.putIfAbsent(l.getId(), l));
        Map<Long, LotteonOrderLine> mirrors = mirrorsOf(new ArrayList<>(bundle.values()));
        List<RecordOutcome> outcomes = new ArrayList<>();
        List<FailedBox> failed = new ArrayList<>();
        int succeeded = 0;
        for (RecordChoice choice : choices) {
            String procSeq = choice.procSeq() == null ? null : choice.procSeq().trim();
            OrderLine line = bundle.get(choice.orderItemId());
            LotteonOrderLine mirror = line == null ? null : mirrors.get(line.getId());
            JsonNode record = mirror == null ? null : LotteonOrderRows.shipmentRecords(states, mirror.getOdSeq())
                    .stream()
                    .filter(row -> procSeq != null && procSeq.equals(LotteonOrderRows.text(row, "procSeq")))
                    .findFirst()
                    .orElse(null);
            if (record == null || !MODIFIABLE_STEPS.contains(LotteonOrderRows.text(record, "odPrgsStepCd"))) {
                outcomes.add(new RecordOutcome(choice.orderItemId(), choice.procSeq(), null, false,
                        NO_RECORD_MESSAGE));
                failed.add(new FailedBox(boxId, ERROR, NO_RECORD_MESSAGE));
                continue;
            }
            String oldInvoice = LotteonOrderRows.text(record, "invcNo");
            FailedBox failure = oldInvoice == null
                    ? informOne(account, line, mirror, LotteonOrderRows.text(record, "procSeq"), carrierCode,
                            invoiceNumber, boxId)
                    : modifyOne(account, mirror, record, oldInvoice, carrierCode, invoiceNumber, boxId);
            if (failure == null) {
                succeeded++;
                outcomes.add(new RecordOutcome(choice.orderItemId(), choice.procSeq(), oldInvoice, true, null));
            } else {
                failed.add(failure);
                outcomes.add(new RecordOutcome(choice.orderItemId(), choice.procSeq(), oldInvoice, false,
                        failure.message()));
            }
        }
        log.info("롯데ON 송장 수정: orderId={} box={} records={} succeeded={}", externalOrderId, boxId,
                choices.size(), succeeded);
        return new ManualShipmentResult(externalOrderId, boxId, MODE_UPDATE, choices.size(), succeeded, failed, null,
                outcomes);
    }

    /** D19 ①: one [139] call with only this record; null = accepted. */
    private FailedBox modifyOne(MarketplaceAccount account, LotteonOrderLine mirror, JsonNode record,
                                String oldInvoice, String carrierCode, String invoiceNumber, String boxId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("odNo", mirror.getOdNo());
        item.put("odSeq", mirror.getOdSeq());
        item.put("procSeq", LotteonOrderRows.text(record, "procSeq"));
        item.put("invcNo", oldInvoice);
        item.put("modDvCoCd", carrierCode);
        item.put("modInvcNo", invoiceNumber);
        try {
            lotteonOrderApi.modifyInvoice(account, List.of(item));
            return null;
        } catch (Exception e) {
            log.warn("롯데ON 송장 수정 실패: odNo={} odSeq={}", mirror.getOdNo(), mirror.getOdSeq(), e);
            return new FailedBox(boxId, ERROR, e.getMessage());
        }
    }

    /** D19 ⑤: the record has no invoice yet — one [298] line with the record's {@code procSeq}; no write-back. */
    private FailedBox informOne(MarketplaceAccount account, OrderLine line, LotteonOrderLine mirror, String procSeq,
                                String carrierCode, String invoiceNumber, String boxId) {
        try {
            List<LineFailure> failures = lotteonOrderApi.informProgress(account,
                    List.of(progressLine(line, mirror, procSeq, carrierCode, invoiceNumber)));
            if (failures.isEmpty()) {
                return null;
            }
            return new FailedBox(boxId, failures.get(0).code(), failures.get(0).message());
        } catch (Exception e) {
            log.warn("롯데ON 송장 수정(발송 통보) 실패: odNo={} odSeq={}", mirror.getOdNo(), mirror.getOdSeq(), e);
            return new FailedBox(boxId, ERROR, e.getMessage());
        }
    }

    /** Lines of the anchor's order, sales channel and bundle; none found → the anchor alone. */
    private List<OrderLine> bundleLines(OrderLine anchor, MarketplaceAccount account, String boxId) {
        List<OrderLine> lines = orderLineRepository.findByExternalOrderId(anchor.getOrder().getExternalOrderId())
                .stream()
                .filter(l -> l.getOrder().getMarketplaceAccount().getId().equals(account.getId()))
                .filter(l -> boxId.equals(InvoiceWriteBack.boxIdOf(l)))
                .toList();
        return lines.isEmpty() ? List.of(anchor) : lines;
    }

    /** The physical box before sending — a failure is only logged (the invoice still goes out). */
    private void recordParcel(OrderLine anchor, String invoiceNumber, String carrierCode) {
        try {
            shipmentParcelRecorder.record(anchor.getOrderShipment(), invoiceNumber, carrierCode, null);
        } catch (Exception e) {
            log.warn("롯데ON 단건 발송처리 실물 박스 저장 실패(전송은 계속): orderId={}",
                    anchor.getOrder().getExternalOrderId(), e);
        }
    }

    /** Result file · single invoice: per sales channel, {@value #CHUNK} lines per [298] call. */
    private SendOutcome sendByAccount(List<OrderLine> lines, Function<OrderLine, String> invoiceOf,
                                      String carrierCode) {
        List<FailedBox> failed = new ArrayList<>();
        List<LineSend> sends = prepare(lines, mirrorsOf(lines), invoiceOf, failed);
        Map<Long, List<LineSend>> byAccount = new LinkedHashMap<>();
        for (LineSend send : sends) {
            byAccount.computeIfAbsent(send.line().getOrder().getMarketplaceAccount().getId(), k -> new ArrayList<>())
                    .add(send);
        }
        int sent = 0;
        int accepted = 0;
        for (List<LineSend> accountSends : byAccount.values()) {
            MarketplaceAccount account = accountSends.get(0).line().getOrder().getMarketplaceAccount();
            for (int start = 0; start < accountSends.size(); start += CHUNK) {
                List<LineSend> chunk = accountSends.subList(start, Math.min(start + CHUNK, accountSends.size()));
                ChunkResult result = sendChunk(account, chunk, carrierCode);
                sent += chunk.size();
                accepted += result.accepted();
                failed.addAll(result.failed());
            }
        }
        return new SendOutcome(sent, accepted, failed);
    }

    /** Lines to send: a fully cancelled line is left out; a line without its mirror fails. */
    private List<LineSend> prepare(List<OrderLine> lines, Map<Long, LotteonOrderLine> mirrors,
                                   Function<OrderLine, String> invoiceOf, List<FailedBox> failed) {
        List<LineSend> sends = new ArrayList<>();
        for (OrderLine line : lines) {
            if (line.isFullyCancelled()) {
                continue;
            }
            LotteonOrderLine mirror = mirrors.get(line.getId());
            if (mirror == null) {
                failed.add(new FailedBox(InvoiceWriteBack.boxIdOf(line), ERROR, NO_MIRROR_MESSAGE));
                continue;
            }
            sends.add(new LineSend(line, mirror, invoiceOf.apply(line)));
        }
        return sends;
    }

    /** One [298] call; the accepted lines are written back as {@code SHIPPED}. */
    private ChunkResult sendChunk(MarketplaceAccount account, List<LineSend> chunk, String carrierCode) {
        if (chunk.isEmpty()) {
            return new ChunkResult(0, List.of());
        }
        List<Map<String, Object>> body = chunk.stream()
                .map(s -> progressLine(s.line(), s.mirror(), s.mirror().getProcSeq(), carrierCode, s.invoice()))
                .toList();
        List<FailedBox> failed = new ArrayList<>();
        List<LineFailure> failures;
        try {
            failures = lotteonOrderApi.informProgress(account, body);
        } catch (Exception e) {
            log.warn("롯데ON 발송처리 실패: account={} lines={}", account.getId(), chunk.size(), e);
            chunk.forEach(s -> failed.add(new FailedBox(InvoiceWriteBack.boxIdOf(s.line()), ERROR, e.getMessage())));
            return new ChunkResult(0, failed);
        }
        Map<String, LineFailure> failureByKey = new LinkedHashMap<>();
        failures.forEach(f -> failureByKey.putIfAbsent(key(f.odNo(), f.odSeq()), f));
        List<String> acceptedKeys = new ArrayList<>();
        Map<String, List<OrderLine>> acceptedLines = new LinkedHashMap<>();
        for (LineSend send : chunk) {
            String key = key(send.mirror().getOdNo(), send.mirror().getOdSeq());
            LineFailure failure = failureByKey.get(key);
            if (failure != null) {
                failed.add(new FailedBox(InvoiceWriteBack.boxIdOf(send.line()), failure.code(), failure.message()));
                continue;
            }
            acceptedKeys.add(key);
            acceptedLines.computeIfAbsent(key, k -> new ArrayList<>()).add(send.line());
        }
        if (!acceptedKeys.isEmpty()) {
            invoiceWriteBack.markDeparted(acceptedKeys, acceptedLines);
        }
        return new ChunkResult(acceptedKeys.size(), failed);
    }

    /** D18: one [298] delivery line, step 13. */
    private static Map<String, Object> progressLine(OrderLine line, LotteonOrderLine mirror, String procSeq,
                                                    String carrierCode, String invoiceNumber) {
        int orderQty = line.getOrderQty() == null ? 0 : line.getOrderQty();
        int cancelQty = line.getCancelQty() == null ? 0 : line.getCancelQty();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dvRtrvDvsCd", "DV");
        item.put("odNo", mirror.getOdNo());
        item.put("odSeq", mirror.getOdSeq());
        item.put("procSeq", procSeq);
        item.put("odPrgsStepCd", STEP_SHIPPED);
        item.put("dvTrcStatDttm", LotteonOrderApi.TIME.format(LocalDateTime.now(SyncWindow.KST)));
        item.put("invcNbr", "1");
        item.put("invcNoList", List.of(invoiceNumber));
        item.put("dvCoCd", carrierCode);
        item.put("slQty", String.valueOf(orderQty - cancelQty));
        return item;
    }

    private Map<Long, LotteonOrderLine> mirrorsOf(List<OrderLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        return lotteonOrderLineRepository.findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList())
                .stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
    }

    private static String key(String odNo, String odSeq) {
        return odNo + "|" + odSeq;
    }

    private record LineSend(OrderLine line, LotteonOrderLine mirror, String invoice) {
    }

    private record ChunkResult(int accepted, List<FailedBox> failed) {
    }

    private record SendOutcome(int sent, int accepted, List<FailedBox> failed) {
    }
}
