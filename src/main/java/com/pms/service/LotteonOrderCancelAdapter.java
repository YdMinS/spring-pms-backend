package com.pms.service;

import com.pms.domain.LotteonOrderLine;
import com.pms.domain.OrderCancelAction;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderCancelActionRepository;
import com.pms.service.OrderCancelResult.CancelledLine;
import com.pms.service.OrderCancelResult.FailedLine;
import com.pms.service.OrderCancelResult.SkippedLine;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonSellerChoices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LotteON seller cancel = seller direct cancel [225] (FEATURE_2611_23 / D5 · D25 · R26) — the same shape as
 * {@link ElevenstOrderCancelAdapter}: one call per line, the whole line (the requested quantity is not sent).
 *
 * <p>The line is not changed here — the next sync reads the cancel from [50] as {@code cancel_qty}. A success is
 * reported as a cancelled line with {@code cancelledQty} 0 and receipt type {@value #LOTTEON_SELLER_CANCEL}; a
 * refusal is a failed line with code {@code ERROR} and LotteON's own text (D44). Every attempt leaves one
 * {@code order_cancel_action} row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteonOrderCancelAdapter implements OrderCancelAdapter {

    /** Receipt type of a LotteON seller direct cancel sent to LotteON (R26). */
    static final String LOTTEON_SELLER_CANCEL = "LOTTEON_SELLER_CANCEL";

    static final String LOTTEON_LINE_MISSING_MESSAGE = "롯데ON 주문 줄 정보가 없습니다";

    private static final String SUCCESS_CODE = "0000";
    private static final String ERROR_CODE = "ERROR";

    private final LotteonOrderApi lotteonOrderApi;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;
    private final OrderCancelActionRepository orderCancelActionRepository;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /** D25: a LotteON line needs a listed {@code reasonCode}; the text is optional (the request caps it at 200). */
    @Override
    public void checkReason(OrderCancelRequest request) {
        reasonOf(request);
    }

    @Override
    public Batch begin(OrderCancelRequest request, List<OrderLine> lines) {
        return new LotteonBatch(request);
    }

    /** The checked LotteON reason: listed code + trimmed text (blank → null). */
    private static LotteonReason reasonOf(OrderCancelRequest request) {
        String code = request.reasonCode() == null ? null : request.reasonCode().trim();
        LotteonSellerChoices.requireLabel(LotteonSellerChoices.SELLER_CANCEL_REASONS, code, REASON_REQUIRED_MESSAGE);
        String text = request.reasonText() == null || request.reasonText().isBlank()
                ? null : request.reasonText().trim();
        return new LotteonReason(code, text);
    }

    /** One request's LotteON lines. */
    private final class LotteonBatch implements Batch {

        private final OrderCancelRequest request;
        private final List<OrderLine> lines = new ArrayList<>();

        private LotteonBatch(OrderCancelRequest request) {
            this.request = request;
        }

        /** D25: the whole line goes to LotteON ([225]) — the quantity is not used. */
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
                cancelLotteon(lines, reasonOf(request), cancelled, failed);
            }
        }
    }

    /** [225] one call per line; never throws. */
    private void cancelLotteon(List<OrderLine> lines, LotteonReason reason, List<CancelledLine> cancelled,
                               List<FailedLine> failed) {
        Map<Long, LotteonOrderLine> mirrors = lotteonOrderLineRepository
                .findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        for (OrderLine line : lines) {
            int remaining = line.purchasableQty();
            LotteonOrderLine mirror = mirrors.get(line.getId());
            if (mirror == null) {
                failed.add(new FailedLine(line.getId(), null, ERROR_CODE, LOTTEON_LINE_MISSING_MESSAGE));
                record(line, remaining, null, reason, false, ERROR_CODE, LOTTEON_LINE_MISSING_MESSAGE);
                continue;
            }
            try {
                lotteonOrderApi.sellerCancel(line.getOrder().getMarketplaceAccount(), body(mirror, reason));
                cancelled.add(new CancelledLine(line.getId(), 0, line.getCancelQty(), line.getHoldQty(),
                        line.purchasableQty(), line.getStatus().name(), null, LOTTEON_SELLER_CANCEL));
                record(line, remaining, mirror.getPlatformStatus(), reason, true, SUCCESS_CODE, null);
                log.info("롯데ON 판매자 직접 취소: line={} odNo={} odSeq={} reason={}", line.getId(), mirror.getOdNo(),
                        mirror.getOdSeq(), reason.code());
            } catch (Exception e) {
                log.warn("롯데ON 판매자 직접 취소 실패: line={}", line.getId(), e);
                failed.add(new FailedLine(line.getId(), mirror.getSitmNo(), ERROR_CODE, e.getMessage()));
                record(line, remaining, mirror.getPlatformStatus(), reason, false, ERROR_CODE, e.getMessage());
            }
        }
    }

    /** [225] body — {@code odNo} + one {@code itemList} line; a blank text is not sent. */
    private static Map<String, Object> body(LotteonOrderLine mirror, LotteonReason reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("odSeq", mirror.getOdSeq());
        if (mirror.getProcSeq() != null) {
            item.put("procSeq", mirror.getProcSeq());
        }
        item.put("slrRsnCd", reason.code());
        if (reason.text() != null) {
            item.put("slrRsnCnts", reason.text());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("odNo", mirror.getOdNo());
        body.put("itemList", List.of(item));
        return body;
    }

    /**
     * One history row (R26): {@code reason} stays {@code null}, the LotteON code goes to {@code platform_reason_code}
     * and the seller's text to {@code reason_text}; the quantity is the line's remaining quantity right before
     * sending. A failed save is only logged.
     */
    private void record(OrderLine line, int quantity, String statusAtSend, LotteonReason reason, boolean succeeded,
                        String code, String message) {
        try {
            orderCancelActionRepository.save(OrderCancelAction.builder()
                    .orderLine(line)
                    .quantity(quantity)
                    .platformReasonCode(reason.code())
                    .reasonText(reason.text())
                    .statusAtSend(statusAtSend)
                    .succeeded(succeeded)
                    .receiptType(LOTTEON_SELLER_CANCEL)
                    .resultCode(code)
                    .resultMessage(OrderCancelAdapter.truncate(message))
                    .createdBy(OrderCancelAdapter.currentUsername())
                    .build());
        } catch (Exception e) {
            log.error("롯데ON 판매자 직접 취소 감사기록 실패: line={} succeeded={}", line.getId(), succeeded, e);
        }
    }

    /** The checked LotteON seller-cancel reason of a request. */
    private record LotteonReason(String code, String text) {
    }
}
