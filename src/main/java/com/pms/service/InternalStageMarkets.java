package com.pms.service;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which marketplaces open the internal-stage bundle (FEATURE_2610_23 / D23 · D24) — the 「내부 상품준비중」 mark ·
 * release, the 「내부 상품준비중」 sheet, the reservation and the internal [지금 발송] open together.
 *
 * <p>The one source is {@link OrderAcknowledgeAdapter#internalStage()}; this component only answers it for the
 * services that do not hold the acknowledge adapters. Today: Coupang and 11st (FEATURE_2611_23 / D51).
 */
@Component
@RequiredArgsConstructor
public class InternalStageMarkets {

    private final List<OrderAcknowledgeAdapter> acknowledgeAdapters;

    /** Whether the marketplace's acknowledge adapter opens the internal stage; no adapter → {@code false}. */
    public boolean supports(Platform platform) {
        return acknowledgeAdapters.stream()
                .anyMatch(adapter -> adapter.platform() == platform && adapter.internalStage());
    }

    /**
     * Lines the internal-stage mark leaves out (FEATURE_2611_23 / D53 · R1): the lines are grouped by their
     * marketplace and each marketplace that opens the internal stage answers for its own lines; the union is returned.
     */
    public Set<Long> excludedLineIds(List<OrderLine> lines) {
        Map<Platform, List<OrderLine>> byPlatform = new EnumMap<>(Platform.class);
        for (OrderLine line : lines) {
            Platform platform = line.getOrder().getMarketplaceAccount().getPlatform();
            byPlatform.computeIfAbsent(platform, k -> new ArrayList<>()).add(line);
        }
        Set<Long> excluded = new HashSet<>();
        for (Map.Entry<Platform, List<OrderLine>> entry : byPlatform.entrySet()) {
            acknowledgeAdapters.stream()
                    .filter(adapter -> adapter.platform() == entry.getKey() && adapter.internalStage())
                    .findFirst()
                    .ifPresent(adapter -> excluded.addAll(adapter.internalStageExcludedLineIds(entry.getValue())));
        }
        return excluded;
    }

    /** D27 · D29 — 결제완료(PAID) 라인의 배송 묶음에 내부 단계가 있으면 [지금 발송]이 발주처리부터 한다. */
    public static boolean hasInternalStage(List<OrderLine> lines) {
        return lines.stream().anyMatch(l -> l.getStatus() == OrderStatus.PAID && l.getOrderShipment() != null
                && l.getOrderShipment().getInternalStage() != null);
    }
}
