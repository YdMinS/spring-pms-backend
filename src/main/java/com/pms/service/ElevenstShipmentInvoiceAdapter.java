package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstOrderActions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 11st invoice registration — shipping 1888, one call per delivery number (FEATURE_2610_17 / D2 · D21 · D47 · D64),
 * moved from {@link ShipmentConfirmServiceImpl} unchanged (FEATURE_2610_23 / D3 · D21). The internal-stage slot stays
 * empty for 11st (D24).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstShipmentInvoiceAdapter implements ShipmentInvoiceAdapter {

    /** FEATURE_2610_17 / D2 · D20: an 11st paid order needs [발주처리] before its invoice. */
    public static final String ELEVENST_ACKNOWLEDGE_FIRST_MESSAGE =
            "11번가 주문은 [발주처리] 뒤에 송장을 등록할 수 있습니다.";
    /** FEATURE_2610_17 / D21: 11st has no invoice-update API. */
    public static final String ELEVENST_NO_INVOICE_UPDATE_MESSAGE =
            "11번가는 송장 수정을 지원하지 않습니다. 11번가 셀러오피스에서 고쳐 주세요.";
    /** Same wording as the Coupang manual path when a line has no box / delivery number. */
    private static final String NO_BOX_MESSAGE = "박스 ID가 없습니다. 주문동기화 후 다시 시도하세요";

    private final OrderLineRepository orderLineRepository;
    private final CarrierCodeService carrierCodeService;
    private final ShipmentParcelRecorder shipmentParcelRecorder;
    /** FEATURE_2610_17 / D2: 11st shipping 1888 (one call per delivery number). */
    private final ElevenstOrderActions elevenstOrderActions;
    /** FEATURE_2610_17 / D64: 11st partial (1636) and split (6791) single shipments. */
    private final ElevenstManualShipper elevenstManualShipper;
    private final InvoiceWriteBack invoiceWriteBack;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    /**
     * 11st orders of a result file (FEATURE_2610_17 / D2 · D21 · D47).
     *
     * <ul>
     *   <li>Send target = lines in {@code PREPARING} (order confirmation done). An order with none of those is skipped:
     *       paid → 「11번가에서 발주처리를 먼저 해 주세요.」, otherwise 「이미 발송처리된 상태입니다.」.</li>
     *   <li>One 1888 call per delivery number ({@code dlvNo}) with the representative (first) invoice of the first
     *       order seen under it — orders bundled under that number are shipped by the same call. Carrier = the
     *       active carrier's 11st code; missing → every 11st delivery number fails with that message.</li>
     *   <li>A delivery number that fails is reported and the next one is still sent. Success → those lines become
     *       {@code SHIPPED} locally (same write-back as Coupang).</li>
     * </ul>
     * ⚠️ Never throws — every failure is a {@code FailedBox} (other orders of the same file keep their results).
     */
    @Override
    public FileOutcome shipFromFile(ResultFile file, Map<String, List<OrderLine>> orders) {
        Map<String, List<String>> invoicesByOrderId = file.invoicesByOrderId();
        List<FailedBox> failed = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        Map<String, List<OrderLine>> linesByDlvNo = new LinkedHashMap<>();
        Map<String, String> invoiceByDlvNo = new LinkedHashMap<>();
        Map<String, MarketplaceAccount> accountByDlvNo = new LinkedHashMap<>();
        int matched = 0;
        for (Map.Entry<String, List<OrderLine>> order : orders.entrySet()) {
            String orderId = order.getKey();
            List<OrderLine> lines = order.getValue();
            invoiceWriteBack.recordParcels(orderId, lines, invoicesByOrderId.get(orderId), file.carrierCodeCache());
            List<OrderLine> preparing = lines.stream().filter(l -> l.getStatus() == OrderStatus.PREPARING).toList();
            if (preparing.isEmpty()) {
                boolean paid = lines.stream().anyMatch(l -> l.getStatus() == OrderStatus.PAID);
                skipped.add(new SkippedOrder(orderId, InvoiceWriteBack.statusName(lines.get(0)), paid
                        ? ShipmentConfirmResult.ELEVENST_ACKNOWLEDGE_FIRST_REASON
                        : ShipmentConfirmResult.ALREADY_SHIPPED_REASON));
                continue;
            }
            matched++;
            for (OrderLine line : preparing) {
                String dlvNo = InvoiceWriteBack.boxIdOf(line);
                if (dlvNo == null || dlvNo.isBlank()) {
                    failed.add(new FailedBox("", "ERROR", NO_BOX_MESSAGE));
                    continue;
                }
                linesByDlvNo.computeIfAbsent(dlvNo, k -> new ArrayList<>()).add(line);
                invoiceByDlvNo.putIfAbsent(dlvNo, InvoiceWriteBack.representativeInvoice(invoicesByOrderId, orderId));
                accountByDlvNo.putIfAbsent(dlvNo, line.getOrder().getMarketplaceAccount());
            }
        }
        if (linesByDlvNo.isEmpty()) {
            return new FileOutcome(matched, 0, failed, skipped, List.of());
        }
        String carrierCode;
        try {
            carrierCode = carrierCodeService.resolveDeliveryCompanyCode(Platform.ELEVENST);
        } catch (Exception e) {
            linesByDlvNo.keySet().forEach(dlvNo -> failed.add(new FailedBox(dlvNo, "ERROR", e.getMessage())));
            return new FileOutcome(matched, 0, failed, skipped, List.of());
        }
        int succeeded = 0;
        for (Map.Entry<String, List<OrderLine>> entry : linesByDlvNo.entrySet()) {
            String dlvNo = entry.getKey();
            try {
                elevenstOrderActions.ship(accountByDlvNo.get(dlvNo), dlvNo, carrierCode, invoiceByDlvNo.get(dlvNo),
                        LocalDateTime.now(SyncWindow.KST));
                succeeded++;
                invoiceWriteBack.markDeparted(List.of(dlvNo), Map.of(dlvNo, entry.getValue()));
            } catch (Exception e) {
                log.warn("11번가 발송처리 실패: dlvNo={}", dlvNo, e);
                failed.add(new FailedBox(dlvNo, "ERROR", e.getMessage()));
            }
        }
        log.info("11번가 발송처리: orders={} matched={} dlvNos={} succeeded={} failed={} skipped={}",
                orders.size(), matched, linesByDlvNo.size(), succeeded, failed.size(), skipped.size());
        return new FileOutcome(matched, succeeded, failed, skipped, List.of());
    }

    /**
     * Single 11st invoice (FEATURE_2610_17 / D2 · D20 · D21): only a {@code PREPARING} line, one 1888 call for its
     * delivery number with the carrier the screen chose. Paid → 400 (D20), already shipped → 400 (D21, no update
     * API). A rejected call comes back as one failed box (no exception — same shape as the Coupang path).
     * {@code PARTIAL} · {@code SPLIT} go to {@link ElevenstManualShipper} after the same checks (D64).
     */
    @Override
    public ManualShipmentResult shipManual(ManualShipmentRequest request, OrderLine anchor,
                                           MarketplaceAccount account, String invoiceNumber) {
        String dlvNo = InvoiceWriteBack.boxIdOf(anchor);
        if (dlvNo == null || dlvNo.isBlank()) {
            throw new IllegalArgumentException(NO_BOX_MESSAGE);
        }
        if (anchor.getStatus() == OrderStatus.PAID) {
            throw new IllegalArgumentException(ELEVENST_ACKNOWLEDGE_FIRST_MESSAGE);
        }
        if (anchor.getStatus() != null && anchor.getStatus().isTerminal()) {
            throw new IllegalArgumentException(ELEVENST_NO_INVOICE_UPDATE_MESSAGE);
        }
        String externalOrderId = anchor.getOrder().getExternalOrderId();
        String carrierCode = carrierCodeService.validateDeliveryCompanyCode(request.deliveryCompanyCode(),
                Platform.ELEVENST);
        if (ManualShipmentRequest.MODE_PARTIAL.equals(request.mode())
                || ManualShipmentRequest.MODE_SPLIT.equals(request.mode())) {
            return elevenstManualShipper.ship(request, anchor, account, carrierCode, invoiceNumber);
        }
        List<OrderLine> lines = orderLineRepository.findByExternalOrderId(externalOrderId).stream()
                .filter(l -> l.getOrder().getMarketplaceAccount().getId().equals(account.getId()))
                .filter(l -> dlvNo.equals(InvoiceWriteBack.boxIdOf(l)))
                .toList();
        if (lines.isEmpty()) {
            lines = List.of(anchor);
        }
        try {
            shipmentParcelRecorder.record(anchor.getOrderShipment(), invoiceNumber, carrierCode, null);
        } catch (Exception e) {
            log.warn("11번가 단건 발송처리 실물 박스 저장 실패(전송은 계속): orderId={} dlvNo={}", externalOrderId, dlvNo, e);
        }
        try {
            elevenstOrderActions.ship(account, dlvNo, carrierCode, invoiceNumber, LocalDateTime.now(SyncWindow.KST));
        } catch (Exception e) {
            log.warn("11번가 단건 발송처리 실패: orderItemId={} dlvNo={}", request.orderItemId(), dlvNo, e);
            return new ManualShipmentResult(externalOrderId, dlvNo, "CREATE", lines.size(), 0,
                    List.of(new FailedBox(dlvNo, "ERROR", e.getMessage())), null);
        }
        invoiceWriteBack.markDeparted(List.of(dlvNo), Map.of(dlvNo, lines));
        return new ManualShipmentResult(externalOrderId, dlvNo, "CREATE", lines.size(), 1, List.of(),
                InvoiceWriteBack.STATUS_SHIPPED.name());
    }
}
