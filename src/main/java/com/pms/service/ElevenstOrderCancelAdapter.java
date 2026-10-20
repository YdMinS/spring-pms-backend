package com.pms.service;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.OrderCancelAction;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderCancelActionRepository;
import com.pms.service.OrderCancelResult.CancelledLine;
import com.pms.service.OrderCancelResult.FailedLine;
import com.pms.service.OrderCancelResult.SkippedLine;
import com.pms.service.elevenst.ElevenstRejectedException;
import com.pms.service.elevenst.ElevenstSellerActions;
import com.pms.service.elevenst.ElevenstSellerChoices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 11st seller cancel = sale refusal 1638 (FEATURE_2610_17 / D61 · D70 · D71), moved from {@link OrderCancelServiceImpl}
 * unchanged (FEATURE_2610_23 / D3 · D21).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstOrderCancelAdapter implements OrderCancelAdapter {

    /** Receipt type of an 11st sale refusal sent to 11st (FEATURE_2610_17 / D61). */
    static final String ELEVENST_SALE_REFUSAL = "ELEVENST_SALE_REFUSAL";

    static final String ELEVENST_REASON_TEXT_MESSAGE = "사유 글은 1~200자로 적어 주세요.";
    private static final int ELEVENST_REASON_TEXT_MAX = 200;

    /** FEATURE_2610_17 / D61: 11st sale refusal (1638). */
    private final ElevenstSellerActions elevenstSellerActions;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final OrderCancelActionRepository orderCancelActionRepository;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    /** FEATURE_2610_17 / D71: an 11st line needs a listed {@code reasonCode} and a 1~200 character {@code reasonText}. */
    @Override
    public void checkReason(OrderCancelRequest request) {
        reasonOf(request);
    }

    @Override
    public Batch begin(OrderCancelRequest request, List<OrderLine> lines) {
        return new ElevenstBatch(request);
    }

    /** The checked 11st reason: listed code + trimmed 1~200 character text (D71). */
    private static ElevenstReason reasonOf(OrderCancelRequest request) {
        String code = request.reasonCode() == null ? null : request.reasonCode().trim();
        ElevenstSellerChoices.requireLabel(ElevenstSellerChoices.SELLER_CANCEL_REASONS, code, REASON_REQUIRED_MESSAGE);
        String text = request.reasonText() == null ? "" : request.reasonText().trim();
        if (text.isEmpty() || text.length() > ELEVENST_REASON_TEXT_MAX) {
            throw new IllegalArgumentException(ELEVENST_REASON_TEXT_MESSAGE);
        }
        return new ElevenstReason(code, text);
    }

    /** One request's 11st lines. */
    private final class ElevenstBatch implements Batch {

        private final OrderCancelRequest request;
        private final List<OrderLine> lines = new ArrayList<>();

        private ElevenstBatch(OrderCancelRequest request) {
            this.request = request;
        }

        /** FEATURE_2610_17 / D61: the whole line goes to 11st (1638) — the quantity is not used. */
        @Override
        public void add(OrderLine line, int quantity, List<SkippedLine> skipped, List<SkippedLine> unsupported) {
            if (line.getStatus() == null || !CANCELLABLE_STATUSES.contains(line.getStatus())) {
                skipped.add(OrderCancelAdapter.skippedLine(line, "취소할 수 없는 상태입니다"));
            } else if (line.isFullyCancelled()) {
                skipped.add(OrderCancelAdapter.skippedLine(line, "이미 전량 취소된 주문입니다"));
            } else {
                lines.add(line);
            }
        }

        @Override
        public void send(List<CancelledLine> cancelled, List<FailedLine> failed) {
            if (!lines.isEmpty()) {
                cancelElevenst(lines, reasonOf(request), cancelled, failed);
            }
        }
    }

    /**
     * 11st sale refusal (1638, FEATURE_2610_17 / D61): one call per line, the whole line. The line is not changed
     * here — the next sync reads it from the 11st cancel-completed list (1642) as {@code cancel_qty} (D14). A success
     * is reported as a cancelled line with {@code cancelledQty} 0 and receipt type
     * {@value #ELEVENST_SALE_REFUSAL}; a refusal keeps 11st's own code and wording. Every attempt reported to the user
     * leaves one history row (D70).
     */
    private void cancelElevenst(List<OrderLine> lines, ElevenstReason reason, List<CancelledLine> cancelled,
                                List<FailedLine> failed) {
        Map<Long, ElevenstOrderLine> mirrors = elevenstOrderLineRepository
                .findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        for (OrderLine line : lines) {
            ElevenstOrderLine mirror = mirrors.get(line.getId());
            if (mirror == null) {
                failed.add(new FailedLine(line.getId(), null, "ERROR", "11번가 주문 정보가 없습니다"));
                recordElevenst(line, null, reason, false, "ERROR", "11번가 주문 정보가 없습니다");
                continue;
            }
            try {
                ElevenstSellerActions.Outcome outcome = elevenstSellerActions.rejectOrder(
                        line.getOrder().getMarketplaceAccount(), mirror, reason.code(), reason.text());
                cancelled.add(new CancelledLine(line.getId(), 0, line.getCancelQty(), line.getHoldQty(),
                        line.purchasableQty(), line.getStatus().name(), null, ELEVENST_SALE_REFUSAL));
                recordElevenst(line, mirror.getPlatformStatus(), reason, true, outcome.code(), outcome.text());
                log.info("11번가 판매불가 처리: line={} ordNo={} reason={}", line.getId(), mirror.getOrdNo(),
                        reason.code());
            } catch (ElevenstRejectedException e) {
                String code = e.getResultCode() == null ? "ERROR" : e.getResultCode();
                String message = e.getResultText() == null ? e.getMessage() : e.getResultText();
                failed.add(new FailedLine(line.getId(), mirror.getPrdStckNo(), code, message));
                recordElevenst(line, mirror.getPlatformStatus(), reason, false, code, message);
            } catch (Exception e) {
                log.warn("11번가 판매불가 처리 실패: line={}", line.getId(), e);
                failed.add(new FailedLine(line.getId(), mirror.getPrdStckNo(), "ERROR", e.getMessage()));
                recordElevenst(line, mirror.getPlatformStatus(), reason, false, "ERROR", e.getMessage());
            }
        }
    }

    /**
     * One history row of an 11st sale refusal (FEATURE_2610_17 / D70): {@code reason} stays {@code null}, the 11st
     * code goes to {@code platform_reason_code} and the seller's text to {@code reason_text}; the quantity is the
     * line's remaining quantity right before sending. A failed save is only logged, like the Coupang history row.
     */
    private void recordElevenst(OrderLine line, String statusAtSend, ElevenstReason reason, boolean succeeded,
                                String code, String message) {
        try {
            orderCancelActionRepository.save(OrderCancelAction.builder()
                    .orderLine(line)
                    .quantity(line.purchasableQty())
                    .platformReasonCode(reason.code())
                    .reasonText(reason.text())
                    .statusAtSend(statusAtSend)
                    .succeeded(succeeded)
                    .receiptType(ELEVENST_SALE_REFUSAL)
                    .resultCode(code)
                    .resultMessage(OrderCancelAdapter.truncate(message))
                    .createdBy(OrderCancelAdapter.currentUsername())
                    .build());
        } catch (Exception e) {
            log.error("11번가 판매불가 감사기록 실패: line={} succeeded={}", line.getId(), succeeded, e);
        }
    }

    /** The checked 11st sale-refusal reason of a request: listed code + trimmed 1~200 character text (D71). */
    private record ElevenstReason(String code, String text) {
    }
}
