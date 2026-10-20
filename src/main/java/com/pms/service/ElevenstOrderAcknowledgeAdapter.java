package com.pms.service;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.elevenst.ElevenstOrderActions;
import com.pms.service.elevenst.ElevenstOrderList;
import com.pms.service.elevenst.ElevenstRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 11st order confirmation 1634 (FEATURE_2610_17 / D20 · D48 · D67), moved from {@link OrderAcknowledgeServiceImpl}
 * unchanged (FEATURE_2610_23 / D3 · D21). The internal-stage bundle stays closed for 11st (D24).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstOrderAcknowledgeAdapter implements OrderAcknowledgeAdapter {

    private final ElevenstOrderActions elevenstOrderActions;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final OrderLineRepository orderLineRepository;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    /**
     * Groups the lines by (account, delivery number) — FEATURE_2610_17 / D48 — and sends them. The succeeded delivery
     * numbers are not reported: 11st has no internal stage (FEATURE_2610_23 / D24).
     */
    @Override
    public AcknowledgeOutcome acknowledge(List<OrderLine> lines) {
        Map<String, List<OrderLine>> groups = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            String key = line.getOrder().getMarketplaceAccount().getId() + "|"
                    + line.getOrderShipment().getExternalShipmentId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }
        ElevenstAckOutcome outcome = acknowledgeElevenst(groups);
        return new AcknowledgeOutcome(outcome.groups(), outcome.succeeded(), outcome.failed(), outcome.reserved(),
                List.of());
    }

    /**
     * 11st order confirmation (FEATURE_2610_17 / D20 · D48): within each delivery number, one 1634 call per product
     * number ({@code prdNo}); 11st confirms the other lines of that group with the same call, so every line of a
     * succeeded group becomes {@code PREPARING}. −3206 「이미 발주 처리 되었거나 발주 처리할 건이 없음」 counts as
     * success. A failed group is reported with its delivery number and the next group is still sent. A reserved-sale
     * line (raw 201) is never sent — its order comes back as skipped until [입고완료 처리] (D67).
     *
     * @param linesByAccountAndDlvNo paid 11st lines keyed by {@code accountId|dlvNo}
     */
    private ElevenstAckOutcome acknowledgeElevenst(Map<String, List<OrderLine>> linesByAccountAndDlvNo) {
        List<Long> lineIds = linesByAccountAndDlvNo.values().stream().flatMap(List::stream)
                .map(OrderLine::getId).toList();
        Map<Long, ElevenstOrderLine> mirrors = elevenstOrderLineRepository.findByOrderLine_IdIn(lineIds).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        int groups = 0;
        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        List<OrderLine> confirmed = new ArrayList<>();
        List<SkippedOrder> reserved = new ArrayList<>();
        Set<String> reservedOrders = new LinkedHashSet<>();
        for (List<OrderLine> dlvLines : linesByAccountAndDlvNo.values()) {
            MarketplaceAccount account = dlvLines.get(0).getOrder().getMarketplaceAccount();
            String dlvNo = dlvLines.get(0).getOrderShipment().getExternalShipmentId();
            Map<String, List<OrderLine>> byProduct = new LinkedHashMap<>();
            Map<String, ElevenstOrderLine> anchorByProduct = new HashMap<>();
            for (OrderLine line : dlvLines) {
                ElevenstOrderLine mirror = mirrors.get(line.getId());
                if (mirror == null) {
                    log.warn("11번가 발주처리 라인 제외 — 11번가 거울 행 없음: line={}", line.getId());
                    continue;
                }
                // D67: a reserved-sale line (raw 201) waits for [입고완료 처리] — never confirmed, reported as skipped.
                if (ElevenstOrderList.RESERVED.code().equals(mirror.getPlatformStatus())) {
                    String orderId = line.getOrder().getExternalOrderId();
                    if (reservedOrders.add(orderId)) {
                        reserved.add(new SkippedOrder(orderId, line.getStatus().name(), null));
                    }
                    continue;
                }
                String prdNo = mirror.getPrdNo() == null ? "" : mirror.getPrdNo();
                byProduct.computeIfAbsent(prdNo, k -> new ArrayList<>()).add(line);
                anchorByProduct.putIfAbsent(prdNo, mirror);
            }
            for (Map.Entry<String, List<OrderLine>> group : byProduct.entrySet()) {
                groups++;
                try {
                    elevenstOrderActions.acknowledge(account, anchorByProduct.get(group.getKey()), dlvNo);
                    succeeded++;
                    confirmed.addAll(group.getValue());
                } catch (Exception e) {
                    log.warn("11번가 발주처리 실패: account={} dlvNo={} prdNo={}", account.getId(), dlvNo,
                            group.getKey(), e);
                    failed.add(failedGroup(dlvNo, e));
                }
            }
        }
        if (!confirmed.isEmpty()) {
            try {
                orderLineRepository.saveAll(confirmed.stream()
                        .map(line -> line.toBuilder().status(OrderStatus.PREPARING).build())
                        .toList());
            } catch (Exception e) {
                log.warn("11번가 발주처리 write-back 실패 (11번가 전송은 성공): {}", e.getMessage());
            }
        }
        return new ElevenstAckOutcome(groups, succeeded, failed, reserved);
    }

    /**
     * Failed 11st group row (D54): delivery number + 11st's {@code result_code} / {@code result_text} as given; a
     * call that never got a result (network, unreadable body) is {@code ERROR} + the exception text, like a Coupang
     * chunk failure.
     */
    private static FailedBox failedGroup(String dlvNo, Exception e) {
        if (e instanceof ElevenstRejectedException rejected && rejected.getResultCode() != null) {
            return new FailedBox(dlvNo, rejected.getResultCode(),
                    rejected.getResultText() == null ? "" : rejected.getResultText());
        }
        return new FailedBox(dlvNo, "ERROR", e.getMessage());
    }

    /**
     * {@link #acknowledgeElevenst} result — groups sent · groups succeeded · failed groups · reserved-sale orders left
     * out (D67).
     */
    private record ElevenstAckOutcome(int groups, int succeeded, List<FailedBox> failed,
                                      List<SkippedOrder> reserved) {
    }
}
