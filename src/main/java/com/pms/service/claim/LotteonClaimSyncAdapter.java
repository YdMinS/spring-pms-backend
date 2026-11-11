package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.Platform;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderRows;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LotteON return / exchange load (FEATURE_2611_23 / D27 · D67 · D68 · D85 · D86 · R29 ~ R32).
 *
 * <p>One round = the four lists once over the last {@value #CLAIM_DAYS} days ([51] returns → [67] return withdrawals
 * → [69] exchanges → [70] exchange withdrawals) → the [140] rows (steps · pickup / re-ship invoices) → D86: open claims
 * received before the window, after the {@code STALE} sweep, are followed by order number with one more [140] call
 * per distinct order.
 *
 * <p>The [140] rows come from the order step of the same round ({@link #syncWithProgress}, no extra [140] call — R31);
 * only the claims-only round ({@link #syncExchanges}) reads [140] itself over 14 one-day windows.
 *
 * <p>⚠️ No transaction annotation here: LotteON HTTP runs here; each upserter call commits on its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteonClaimSyncAdapter implements ClaimSyncAdapter {

    /** D11: claim lists and [140] windows look back this many days. */
    static final int CLAIM_DAYS = 14;

    /** {@code order_claim.item_name} length. */
    private static final int ITEM_NAME_MAX_LENGTH = 500;
    private static final Set<String> CLAIM_TYPES = Set.of("40", "41", "30", "31");
    private static final Set<String> WITHDRAW_TYPES = Set.of("41", "31");
    private static final Set<String> RETURN_TYPES = Set.of("40", "41");

    private final LotteonOrderApi orderApi;
    private final LotteonClaimUpserter claimUpserter;
    private final OrderClaimRepository orderClaimRepository;
    private final ClaimStaleSweeper claimStaleSweeper;
    private final SyncStatusRecorder syncStatusRecorder;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /** Claims-only round: same as the 11st adapter — a failure propagates, only a finished round is stamped. */
    @Override
    public void syncClaimsOnly(MarketplaceAccount account) {
        syncExchanges(account);
        syncStatusRecorder.recordClaimSyncCompleted(account.getId());
        log.info("Claim-only sync (lotteon): account={}", account.getId());
    }

    /** Claims-only round (R31): [140] over 14 one-day windows (oldest first), then {@link #syncWithProgress}. */
    @Override
    public ClaimSyncResult syncExchanges(MarketplaceAccount account) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        List<JsonNode> progressRows = new ArrayList<>();
        for (int back = CLAIM_DAYS; back >= 1; back--) {
            LocalDateTime from = now.minusDays(back);
            LocalDateTime to = from.plusDays(1).minusSeconds(1);
            progressRows.addAll(orderApi.progressStates(account, from, to));
        }
        return syncWithProgress(account, progressRows);
    }

    /**
     * The four lists → the given [140] rows → D86 order-number follow-up. A LotteON failure is thrown on (the order
     * round decides what it means, R39); one row failing to save only skips that row.
     */
    public ClaimSyncResult syncWithProgress(MarketplaceAccount account, List<JsonNode> progressRows) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        LocalDateTime from = now.minusDays(CLAIM_DAYS);
        loadList(account, ClaimType.RETURN, orderApi.returnList(account, from, now), true);
        loadList(account, ClaimType.RETURN, orderApi.returnWithdrawList(account, from, now), false);
        loadList(account, ClaimType.EXCHANGE, orderApi.exchangeList(account, from, now), false);
        loadList(account, ClaimType.EXCHANGE, orderApi.exchangeWithdrawList(account, from, now), false);

        applyRows(account, progressRows);

        int staleClosed = 0;
        Set<String> orderNumbers = new LinkedHashSet<>();
        for (ClaimType type : List.of(ClaimType.RETURN, ClaimType.EXCHANGE)) {
            List<OrderClaim> open = orderClaimRepository.findOpen(account.getId(), type, ClaimStatus.closedStatuses());
            List<OrderClaim> remaining = claimStaleSweeper.sweep(account, open);
            staleClosed += open.size() - remaining.size();
            for (OrderClaim claim : remaining) {
                if (claim.getReceivedAt() != null && claim.getReceivedAt().isBefore(from)
                        && claim.getExternalOrderId() != null) {
                    orderNumbers.add(claim.getExternalOrderId());
                }
            }
        }
        // D86: no cap (the 30-day sweep is the cap, D40); a LotteON failure stops here and is thrown on.
        for (String odNo : orderNumbers) {
            applyRows(account, orderApi.progressStatesOfOrder(account, odNo));
        }
        log.info("[lotteon] claim sync: account={} progressRows={} followedOrders={} staleClosed={}",
                account.getId(), progressRows.size(), orderNumbers.size(), staleClosed);
        return new ClaimSyncResult(4, 0, staleClosed);
    }

    private void loadList(MarketplaceAccount account, ClaimType type, List<JsonNode> rows, boolean returnRequests) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        for (JsonNode row : rows) {
            ClaimRecord record = toRecord(row, now);
            if (record == null) {
                log.warn("[lotteon] claim row without clmNo/odNo/odSeq/odPrgsStepCd or of another type skipped: "
                        + "account={} type={}", account.getId(), type);
                continue;
            }
            // D72: the sync writes the hold of returns only, from [51] excpProcDvsCd.
            String hold = returnRequests && "03".equals(LotteonOrderRows.text(row, "excpProcDvsCd")) ? "03" : null;
            try {
                claimUpserter.upsert(account, type, record, LotteonOrderRows.text(row, "procSeq"),
                        LotteonOrderRows.text(row, "orglProcSeq"), hold);
            } catch (RuntimeException e) {
                log.warn("[lotteon] claim row save failed, skipped: account={} clmNo={} odSeq={}", account.getId(),
                        record.externalClaimId(), record.externalItemId(), e);
            }
        }
    }

    private void applyRows(MarketplaceAccount account, List<JsonNode> rows) {
        for (JsonNode row : rows) {
            if (!LotteonClaimUpserter.isClaimRow(row)) {
                continue;
            }
            try {
                claimUpserter.applyProgress(account, row);
            } catch (RuntimeException e) {
                log.warn("[lotteon] claim progress row save failed, skipped: account={} clmNo={}", account.getId(),
                        LotteonOrderRows.text(row, "clmNo"), e);
            }
        }
    }

    /**
     * One list row → neutral record (R29). Address / contact fields are never read (D9). {@code received_at} is NOT
     * NULL — an unreadable {@code clmReqDttm} falls back to {@code now} (KST).
     *
     * @return null when {@code clmNo} · {@code odNo} · {@code odSeq} · {@code odPrgsStepCd} is missing or
     *         {@code odTypCd} is not 40 · 41 · 30 · 31 (AS 50 is skipped)
     */
    static ClaimRecord toRecord(JsonNode row, LocalDateTime now) {
        String clmNo = LotteonOrderRows.text(row, "clmNo");
        String odNo = LotteonOrderRows.text(row, "odNo");
        String odSeq = LotteonOrderRows.text(row, "odSeq");
        String step = LotteonOrderRows.text(row, "odPrgsStepCd");
        String type = LotteonOrderRows.text(row, "odTypCd");
        if (clmNo == null || odNo == null || odSeq == null || step == null || !CLAIM_TYPES.contains(type)) {
            return null;
        }
        LocalDateTime receivedAt = LotteonOrderApi.parse(LotteonOrderRows.text(row, "clmReqDttm"));
        return new ClaimRecord(
                clmNo, odNo, null, odSeq,
                itemName(row),
                LotteonOrderRows.quantity(row, RETURN_TYPES.contains(type) ? "rtngQty" : "xchgQty"),
                WITHDRAW_TYPES.contains(type) ? ClaimStatus.WITHDRAWN : status(step), step,
                null,
                LotteonOrderRows.text(row, "clmRsnCd"), LotteonOrderRows.text(row, "clmRsnCnts"),
                null, null,
                null, null,
                null, null, null, null,
                receivedAt != null ? receivedAt : now, null);
    }

    /** R30 status table; an unknown code stays open ({@code RECEIVED}), same as the Coupang and 11st claims. */
    static ClaimStatus status(String step) {
        if (step == null) {
            return ClaimStatus.RECEIVED;
        }
        return switch (step.trim()) {
            case "03" -> ClaimStatus.RECEIVED;
            case "23", "24", "25" -> ClaimStatus.IN_PROGRESS;
            case "26", "27" -> ClaimStatus.DONE;
            case "22" -> ClaimStatus.WITHDRAWN;
            default -> ClaimStatus.RECEIVED;
        };
    }

    /** R7: {@code spdNm}, plus {@code / sitmNm} when an item name is present. */
    private static String itemName(JsonNode row) {
        String product = LotteonOrderRows.text(row, "spdNm");
        String item = LotteonOrderRows.text(row, "sitmNm");
        String name = (item == null) ? product : (product == null ? item : product + " / " + item);
        return (name == null || name.length() <= ITEM_NAME_MAX_LENGTH) ? name : name.substring(0, ITEM_NAME_MAX_LENGTH);
    }
}
