package com.pms.service.elevenst;

import com.pms.domain.ElevenstCancelRequest;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.repository.ElevenstCancelRequestRepository;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 11st cancel claims → {@code elevenst_cancel_request} + the order line's hold / cancel quantities
 * (FEATURE_2610_17 / D14 · D40).
 *
 * <ul>
 *   <li>Load: rows of 1639 (requests) · 1642 (completed) · 1724 (withdrawn), key (account, {@code ordPrdCnSeq}).
 *       An existing claim only takes the new status and completion time; {@code REJECTED} (ours) is overwritten
 *       only by 02 / 05 from 11st.</li>
 *   <li>Quantities: {@code hold_qty} = Σ quantity of status 01 · {@code cancel_qty} = Σ quantity of status 02 —
 *       re-summed from this table for every touched order line, never accumulated (D40).</li>
 *   <li>Approve / reject success ({@link #markApproved} · {@link #markRejected}) re-sums the same way.</li>
 * </ul>
 *
 * <p>⚠️ Public methods are the commit unit; callers hold no transaction (11st HTTP stays outside).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElevenstCancelRequestUpserter {

    private final ElevenstCancelRequestRepository cancelRequestRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final OrderLineRepository orderLineRepository;

    /**
     * @param listStatus status of the list the rows came from (01 · 02 · 05) — used when a row has no
     *                   {@code ordCnStatCd} of its own
     * @return number of order lines whose hold / cancel quantity changed
     */
    @Transactional
    public int upsertRows(MarketplaceAccount account, List<Map<String, String>> rows, String listStatus) {
        Set<List<String>> touched = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            String claimNo = text(row, "ordPrdCnSeq");
            String ordNo = text(row, "ordNo");
            String ordPrdSeq = text(row, "ordPrdSeq");
            if (claimNo == null || ordNo == null || ordPrdSeq == null) {
                log.warn("[11st-cancel] row without ordPrdCnSeq/ordNo/ordPrdSeq skipped");
                continue;
            }
            String status = statusOf(row, listStatus);
            LocalDateTime completedAt = ElevenstTimes.parse(text(row, "cnTrtEndDt"));
            Optional<ElevenstCancelRequest> existing =
                    cancelRequestRepository.findByMarketplaceAccount_IdAndOrdPrdCnSeq(account.getId(), claimNo);
            if (existing.isPresent()) {
                ElevenstCancelRequest claim = existing.get();
                boolean keepRejected = ElevenstCancelRequest.STATUS_REJECTED.equals(claim.getPlatformStatus())
                        && ElevenstCancelRequest.STATUS_REQUESTED.equals(status);
                if (!keepRejected) {
                    cancelRequestRepository.save(claim.toBuilder()
                            .platformStatus(status)
                            .completedAt(completedAt != null ? completedAt : claim.getCompletedAt())
                            .build());
                }
            } else {
                cancelRequestRepository.save(ElevenstCancelRequest.builder()
                        .marketplaceAccount(account)
                        .ordNo(ordNo)
                        .ordPrdSeq(ordPrdSeq)
                        .ordPrdCnSeq(claimNo)
                        .quantity(quantity(row))
                        .requestedBy(text(row, "ordCnMnbdCd"))
                        .reasonCode(text(row, "ordCnRsnCd"))
                        .reasonText(trim(text(row, "ordCnDtlsRsn"), 1000))
                        .requestedAt(ElevenstTimes.parse(text(row, "createDt")))
                        .completedAt(completedAt)
                        .platformStatus(status)
                        .build());
            }
            touched.add(List.of(ordNo, ordPrdSeq));
        }
        int changed = 0;
        for (List<String> key : touched) {
            if (resum(account.getId(), key.get(0), key.get(1))) {
                changed++;
            }
        }
        return changed;
    }

    /** D40: approve (1640) succeeded → status 02, completion time = now (KST), quantities re-summed. */
    @Transactional
    public void markApproved(Long requestId, LocalDateTime now) {
        cancelRequestRepository.findWithAccountById(requestId).ifPresent(claim -> {
            cancelRequestRepository.save(claim.toBuilder()
                    .platformStatus(ElevenstCancelRequest.STATUS_COMPLETED)
                    .completedAt(now)
                    .build());
            resum(claim.getMarketplaceAccount().getId(), claim.getOrdNo(), claim.getOrdPrdSeq());
        });
    }

    /** D40: reject (1641) succeeded → {@code REJECTED}, quantities re-summed. */
    @Transactional
    public void markRejected(Long requestId) {
        cancelRequestRepository.findWithAccountById(requestId).ifPresent(claim -> {
            cancelRequestRepository.save(claim.toBuilder()
                    .platformStatus(ElevenstCancelRequest.STATUS_REJECTED)
                    .build());
            resum(claim.getMarketplaceAccount().getId(), claim.getOrdNo(), claim.getOrdPrdSeq());
        });
    }

    /**
     * Links the claims of one order line and re-sums its quantities. No order line loaded yet → nothing to do
     * (the claims stay unlinked until a later sync loads the line).
     *
     * @return true when hold or cancel quantity changed
     */
    private boolean resum(Long accountId, String ordNo, String ordPrdSeq) {
        Optional<ElevenstOrderLine> mirror =
                elevenstOrderLineRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(accountId, ordNo, ordPrdSeq);
        if (mirror.isEmpty()) {
            return false;
        }
        OrderLine line = mirror.get().getOrderLine();
        int hold = 0;
        int cancel = 0;
        for (ElevenstCancelRequest claim
                : cancelRequestRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(accountId, ordNo, ordPrdSeq)) {
            if (claim.getOrderLine() == null) {
                cancelRequestRepository.save(claim.toBuilder().orderLine(line).build());
            }
            if (ElevenstCancelRequest.STATUS_REQUESTED.equals(claim.getPlatformStatus())) {
                hold += claim.getQuantity();
            } else if (ElevenstCancelRequest.STATUS_COMPLETED.equals(claim.getPlatformStatus())) {
                cancel += claim.getQuantity();
            }
        }
        if (line.getHoldQty() == hold && line.getCancelQty() == cancel) {
            return false;
        }
        orderLineRepository.save(line.toBuilder().holdQty(hold).cancelQty(cancel).build());
        return true;
    }

    /** The row's own {@code ordCnStatCd} when it is 01 · 02 · 05, else the list's status. */
    private static String statusOf(Map<String, String> row, String listStatus) {
        String own = text(row, "ordCnStatCd");
        if (ElevenstCancelRequest.STATUS_REQUESTED.equals(own) || ElevenstCancelRequest.STATUS_COMPLETED.equals(own)
                || ElevenstCancelRequest.STATUS_WITHDRAWN.equals(own)) {
            return own;
        }
        return listStatus;
    }

    private static int quantity(Map<String, String> row) {
        String value = text(row, "ordCnQty");
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Map<String, String> row, String field) {
        String value = row.get(field);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
