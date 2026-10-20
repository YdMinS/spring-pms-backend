package com.pms.service;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Which marketplaces open the internal-stage bundle (FEATURE_2610_23 / D23 · D24) — the 「내부 상품준비중」 mark ·
 * release, the 「내부 상품준비중」 sheet, the reservation and the internal [지금 발송] open together.
 *
 * <p>The one source is {@link OrderAcknowledgeAdapter#internalStage()}; this component only answers it for the
 * services that do not hold the acknowledge adapters. Today: Coupang only.
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

    /** D27 · D29 — 결제완료(PAID) 라인의 배송 묶음에 내부 단계가 있으면 [지금 발송]이 발주처리부터 한다. */
    public static boolean hasInternalStage(List<OrderLine> lines) {
        return lines.stream().anyMatch(l -> l.getStatus() == OrderStatus.PAID && l.getOrderShipment() != null
                && l.getOrderShipment().getInternalStage() != null);
    }
}
