package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.LotteonCancelRequest;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.repository.LotteonCancelRequestRepository;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * LotteON cancel list [50] → {@code lotteon_cancel_request} + the order line's cancel quantity (FEATURE_2611_23 /
 * D8 · D24 · R21 · R22).
 *
 * <ul>
 *   <li>Load: one row per (account, {@code clmNo}, {@code odSeq}); only cancel lines ({@code odTypCd} 20) in step
 *       02 · 21 · 22. An existing request only takes the new status; {@code REJECTED} (ours) is kept while LotteON
 *       still says 02.</li>
 *   <li>Quantities: {@code cancel_qty} = Σ quantity of status 21 — re-summed from this table for every touched order
 *       line, never accumulated. The other quantity of the line is not written here (R22).</li>
 *   <li>Approve / reject success ({@link #markApproved} · {@link #markRejected}) re-sums the same way.</li>
 * </ul>
 *
 * <p>⚠️ Public methods are the commit unit; callers hold no transaction (LotteON HTTP stays outside).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteonCancelRequestUpserter {

    /** R21: the order cancel type — 50 (AS) lines are skipped. */
    private static final String CANCEL_TYPE = "20";
    private static final int REASON_TEXT_MAX = 1000;

    private final LotteonCancelRequestRepository cancelRequestRepository;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;
    private final OrderLineRepository orderLineRepository;

    /** @return number of order lines whose cancel quantity changed */
    @Transactional
    public int upsertRows(MarketplaceAccount account, List<JsonNode> rows) {
        Set<List<String>> touched = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String clmNo = LotteonOrderRows.text(row, "clmNo");
            String odNo = LotteonOrderRows.text(row, "odNo");
            String odSeq = LotteonOrderRows.text(row, "odSeq");
            String status = LotteonOrderRows.text(row, "odPrgsStepCd");
            if (clmNo == null || odNo == null || odSeq == null || status == null) {
                log.warn("[lotteon-cancel] row without clmNo/odNo/odSeq/odPrgsStepCd skipped: account={}",
                        account.getId());
                continue;
            }
            if (!CANCEL_TYPE.equals(LotteonOrderRows.text(row, "odTypCd")) || !isKnownStatus(status)) {
                continue;
            }
            Optional<LotteonCancelRequest> existing =
                    cancelRequestRepository.findByMarketplaceAccount_IdAndClmNoAndOdSeq(account.getId(), clmNo, odSeq);
            if (existing.isPresent()) {
                LotteonCancelRequest request = existing.get();
                boolean keepRejected = LotteonCancelRequest.STATUS_REJECTED.equals(request.getPlatformStatus())
                        && LotteonCancelRequest.STATUS_REQUESTED.equals(status);
                if (!keepRejected && !status.equals(request.getPlatformStatus())) {
                    cancelRequestRepository.save(request.toBuilder().platformStatus(status).build());
                }
            } else {
                cancelRequestRepository.save(LotteonCancelRequest.builder()
                        .marketplaceAccount(account)
                        .clmNo(clmNo)
                        .odNo(odNo)
                        .odSeq(odSeq)
                        .procSeq(LotteonOrderRows.text(row, "procSeq"))
                        .orglProcSeq(LotteonOrderRows.text(row, "orglProcSeq"))
                        .quantity(LotteonOrderRows.quantity(row, "cnclQty"))
                        .reasonCode(LotteonOrderRows.text(row, "clmRsnCd"))
                        .reasonText(trim(LotteonOrderRows.text(row, "clmRsnCnts")))
                        .requestedAt(LotteonOrderApi.parse(LotteonOrderRows.text(row, "clmReqDttm")))
                        .platformStatus(status)
                        .build());
            }
            touched.add(List.of(odNo, odSeq));
        }
        int changed = 0;
        for (List<String> key : touched) {
            if (resum(account.getId(), key.get(0), key.get(1))) {
                changed++;
            }
        }
        return changed;
    }

    /** D24 · R22: approve [60] succeeded → status 21, cancel quantity re-summed. */
    @Transactional
    public void markApproved(Long requestId) {
        mark(requestId, LotteonCancelRequest.STATUS_COMPLETED);
    }

    /** D24 · R22: reject [59] succeeded → {@code REJECTED}, cancel quantity re-summed. */
    @Transactional
    public void markRejected(Long requestId) {
        mark(requestId, LotteonCancelRequest.STATUS_REJECTED);
    }

    private void mark(Long requestId, String status) {
        cancelRequestRepository.findWithAccountById(requestId).ifPresent(request -> {
            cancelRequestRepository.save(request.toBuilder().platformStatus(status).build());
            resum(request.getMarketplaceAccount().getId(), request.getOdNo(), request.getOdSeq());
        });
    }

    /**
     * Links the requests of one LotteON order line and re-sums its cancel quantity. No order line loaded yet →
     * nothing to do (the requests stay unlinked until a later sync loads the line).
     *
     * @return true when the cancel quantity changed
     */
    private boolean resum(Long accountId, String odNo, String odSeq) {
        Optional<LotteonOrderLine> mirror =
                lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(accountId, odNo, odSeq);
        if (mirror.isEmpty()) {
            return false;
        }
        OrderLine line = mirror.get().getOrderLine();
        int cancel = 0;
        for (LotteonCancelRequest request
                : cancelRequestRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(accountId, odNo, odSeq)) {
            if (request.getOrderLine() == null) {
                cancelRequestRepository.save(request.toBuilder().orderLine(line).build());
            }
            if (LotteonCancelRequest.STATUS_COMPLETED.equals(request.getPlatformStatus())) {
                cancel += request.getQuantity();
            }
        }
        if (line.getCancelQty() != null && line.getCancelQty() == cancel) {
            return false;
        }
        orderLineRepository.save(line.toBuilder().cancelQty(cancel).build());
        return true;
    }

    private static boolean isKnownStatus(String status) {
        return LotteonCancelRequest.STATUS_REQUESTED.equals(status)
                || LotteonCancelRequest.STATUS_COMPLETED.equals(status)
                || LotteonCancelRequest.STATUS_WITHDRAWN.equals(status);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= REASON_TEXT_MAX ? value : value.substring(0, REASON_TEXT_MAX);
    }
}
