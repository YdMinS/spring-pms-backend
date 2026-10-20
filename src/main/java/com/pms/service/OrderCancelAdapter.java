package com.pms.service;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.service.OrderCancelResult.CancelledLine;
import com.pms.service.OrderCancelResult.FailedLine;
import com.pms.service.OrderCancelResult.SkippedLine;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

/**
 * Per-marketplace seller cancel before shipping — FEATURE_2610_23 / D21, the same shape as {@code ClaimSyncAdapter}.
 * Implementations: {@link CoupangOrderCancelAdapter} · {@link ElevenstOrderCancelAdapter}.
 *
 * <p>{@link OrderCancelServiceImpl} checks the reason of every marketplace in the request first (FEATURE_2610_17 /
 * D71), then opens one {@link Batch} per adapter, hands each line to its marketplace's batch in request order and
 * sends the batches in the platform order (Coupang → 11st). A line of a marketplace without an adapter is
 * unsupported (D14).
 */
public interface OrderCancelAdapter {

    /**
     * Statuses a seller cancel is sent from — Coupang answers any other with a 400 (D2), and 11st sale refusal takes
     * paid · preparing too (FEATURE_2610_17 / D61). An irreversible write, so nothing else is sent.
     */
    Set<OrderStatus> CANCELLABLE_STATUSES = Set.of(OrderStatus.PAID, OrderStatus.PREPARING);

    /** FEATURE_2610_17 / D71: the reason check of both platforms, before anything is sent. */
    String REASON_REQUIRED_MESSAGE = "취소 사유를 선택하세요";

    /** A line that cannot be sent: another marketplace, or no delivery bundle / marketplace row. */
    String UNSUPPORTED_MESSAGE = "쿠팡 · 11번가 주문이 아니거나 배송번호가 없습니다";

    /** The value compared with {@code marketplace_account.platform}. */
    Platform platform();

    /**
     * Checks this marketplace's reason in the request — called only when the request has a line of this marketplace,
     * after the lines are read and before anything is grouped or sent (FEATURE_2610_17 / D71).
     *
     * @throws IllegalArgumentException a missing or unknown reason (→ 400, nothing is sent)
     */
    void checkReason(OrderCancelRequest request);

    /**
     * Opens this marketplace's batch of one request (after every reason check passed).
     *
     * @param lines every line of the request, as read
     */
    Batch begin(OrderCancelRequest request, List<OrderLine> lines);

    /** One request's lines of one marketplace. */
    interface Batch {

        /**
         * Sorts one line of this marketplace: adds it to {@code skipped} / {@code unsupported}, or keeps it to send.
         *
         * @throws IllegalArgumentException a quantity over the cancellable quantity (→ 400, nothing is sent — D3)
         */
        void add(OrderLine line, int quantity, List<SkippedLine> skipped, List<SkippedLine> unsupported);

        /** Sends the kept lines and reports each one. Never throws — a failure is a {@link FailedLine}. */
        void send(List<CancelledLine> cancelled, List<FailedLine> failed);
    }

    /** The skipped / unsupported row of a line. */
    static SkippedLine skippedLine(OrderLine line, String reason) {
        return new SkippedLine(line.getId(), line.getOrder().getExternalOrderId(),
                line.getStatus() == null ? null : line.getStatus().name(), reason);
    }

    /** History text cut to the column size (1000). */
    static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    /** The user who sent the cancel — {@code null} without an authentication. */
    static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
