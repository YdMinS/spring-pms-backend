package com.pms.service;

import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.lotteon.LotteonOrderApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LotteON order confirmation — [210] delivery line receipt {@code ifCplYN} = Y (FEATURE_2611_23 / D16 · D17 · R10).
 *
 * <p>Unit = one chosen order line ({@code odNo} · {@code odSeq} · {@code procSeq} of the mirror), at most
 * {@value #CHUNK} lines per call, per sales channel. A succeeded chunk's lines become {@code PREPARING} and count one
 * by one; a failed chunk is reported once per delivery bundle. A bundle is a succeeded box only when none of its sent
 * lines failed (R10).
 *
 * <p>{@link #internalStage()} is open (D49 ①): the internal sheet ({@code LotteonShippingLabelAdapter}) and the
 * reserved invoice send ({@link LotteonShipmentInvoiceAdapter#sendReservedInvoices}) are both filled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteonOrderAcknowledgeAdapter implements OrderAcknowledgeAdapter {

    /** D16: at most this many lines per [210] call. */
    static final int CHUNK = 100;

    private final LotteonOrderApi lotteonOrderApi;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;
    private final OrderLineRepository orderLineRepository;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /** D49 ①: LotteON opens the internal-stage bundle (both slots are filled). */
    @Override
    public boolean internalStage() {
        return true;
    }

    @Override
    public AcknowledgeOutcome acknowledge(List<OrderLine> lines) {
        List<Long> lineIds = lines.stream().map(OrderLine::getId).toList();
        Map<Long, LotteonOrderLine> mirrors = lotteonOrderLineRepository.findByOrderLine_IdIn(lineIds).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        Map<Long, List<OrderLine>> byAccount = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            if (!mirrors.containsKey(line.getId())) {
                log.warn("롯데ON 발주처리 라인 제외 — 롯데ON 거울 행 없음: line={}", line.getId());
                continue;
            }
            byAccount.computeIfAbsent(line.getOrder().getMarketplaceAccount().getId(), k -> new ArrayList<>())
                    .add(line);
        }
        int targets = 0;
        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        Set<String> failedBoxIds = new LinkedHashSet<>();
        Set<String> sentBoxIds = new LinkedHashSet<>();
        List<OrderLine> confirmed = new ArrayList<>();
        for (List<OrderLine> accountLines : byAccount.values()) {
            MarketplaceAccount account = accountLines.get(0).getOrder().getMarketplaceAccount();
            for (int start = 0; start < accountLines.size(); start += CHUNK) {
                List<OrderLine> chunk = accountLines.subList(start, Math.min(start + CHUNK, accountLines.size()));
                List<Map<String, Object>> body = new ArrayList<>();
                for (OrderLine line : chunk) {
                    LotteonOrderLine mirror = mirrors.get(line.getId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("dvRtrvDvsCd", "DV");
                    item.put("odNo", mirror.getOdNo());
                    item.put("odSeq", mirror.getOdSeq());
                    item.put("procSeq", mirror.getProcSeq());
                    item.put("ifCplYN", "Y");
                    body.add(item);
                    sentBoxIds.add(line.getOrderShipment().getExternalShipmentId());
                }
                targets += chunk.size();
                try {
                    lotteonOrderApi.informIfComplete(account, body);
                    succeeded += chunk.size();
                    confirmed.addAll(chunk);
                } catch (Exception e) {
                    log.warn("롯데ON 발주처리 실패: account={} lines={}", account.getId(), chunk.size(), e);
                    for (OrderLine line : chunk) {
                        String boxId = line.getOrderShipment().getExternalShipmentId();
                        if (failedBoxIds.add(boxId)) {
                            failed.add(new FailedBox(boxId, "ERROR", e.getMessage()));
                        }
                    }
                }
            }
        }
        if (!confirmed.isEmpty()) {
            try {
                orderLineRepository.saveAll(confirmed.stream()
                        .map(line -> line.toBuilder().status(OrderStatus.PREPARING).build())
                        .toList());
            } catch (Exception e) {
                log.warn("롯데ON 발주처리 write-back 실패 (롯데ON 전송은 성공): {}", e.getMessage());
            }
        }
        List<String> succeededBoxIds = sentBoxIds.stream().filter(id -> !failedBoxIds.contains(id)).toList();
        return new AcknowledgeOutcome(targets, succeeded, failed, List.of(), succeededBoxIds);
    }
}
