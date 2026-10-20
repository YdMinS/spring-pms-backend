package com.pms.service.claim;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.Platform;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstPaths;
import com.pms.service.elevenst.ElevenstTimes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 11st return / exchange load (FEATURE_2610_17 / D15 · D25 · D29). Despite the interface method name, the 11st
 * {@link #syncExchanges} loads <b>both</b> returns and exchanges — the 11st round has no Coupang cancel step that
 * would carry the returns.
 *
 * <p>Lists (last 14 days, one window each): returns 1643 · 1646 · 1647, exchanges 1648 · 1651 · 1652. Every row is
 * read the same way; the status comes from {@code clmStat} (D25). Then open 11st claims older than
 * {@code claim-stale-days} are closed as {@code STALE} (same sweeper as Coupang).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstClaimSyncAdapter implements ClaimSyncAdapter {

    /** D29: claim lists look back this many days in one window. */
    static final int CLAIM_DAYS = 14;

    private static final List<String> RETURN_LISTS = List.of(
            ElevenstPaths.RETURN_REQUESTS, ElevenstPaths.RETURN_COMPLETED, ElevenstPaths.RETURN_WITHDRAWN);
    private static final List<String> EXCHANGE_LISTS = List.of(
            ElevenstPaths.EXCHANGE_REQUESTS, ElevenstPaths.EXCHANGE_COMPLETED, ElevenstPaths.EXCHANGE_WITHDRAWN);

    private final ElevenstOrderApi orderApi;
    private final ElevenstClaimUpserter claimUpserter;
    private final OrderClaimRepository orderClaimRepository;
    private final ClaimStaleSweeper claimStaleSweeper;
    /** FEATURE_2610_23 / D12: stamps the claims-only round. */
    private final SyncStatusRecorder syncStatusRecorder;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    /**
     * 11st claims-only round (FEATURE_2610_17 / D15): {@link #syncExchanges} loads returns and exchanges in one call; a
     * failure propagates (same D15-1 contract as the Coupang round). Moved from {@code ClaimSyncServiceImpl} unchanged
     * (FEATURE_2610_23 / D3 · D12).
     */
    @Override
    public void syncClaimsOnly(MarketplaceAccount account) {
        syncExchanges(account);
        syncStatusRecorder.recordClaimSyncCompleted(account.getId());
        log.info("Claim-only sync (11st): account={}", account.getId());
    }

    @Override
    public ClaimSyncResult syncExchanges(MarketplaceAccount account) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        LocalDateTime from = now.minusDays(CLAIM_DAYS);
        int lists = 0;
        for (String path : RETURN_LISTS) {
            load(account, ClaimType.RETURN, orderApi.listByMinute(account, path, "clmReqSeq", from, now));
            lists++;
        }
        for (String path : EXCHANGE_LISTS) {
            load(account, ClaimType.EXCHANGE, orderApi.listByMinute(account, path, "clmReqSeq", from, now));
            lists++;
        }
        int staleClosed = 0;
        for (ClaimType type : List.of(ClaimType.RETURN, ClaimType.EXCHANGE)) {
            List<OrderClaim> open = orderClaimRepository.findOpen(account.getId(), type, ClaimStatus.closedStatuses());
            staleClosed += open.size() - claimStaleSweeper.sweep(account, open).size();
        }
        log.info("[11st] claim sync: account={} lists={} staleClosed={}", account.getId(), lists, staleClosed);
        return new ClaimSyncResult(lists, 0, staleClosed);
    }

    private void load(MarketplaceAccount account, ClaimType type, List<Map<String, String>> rows) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        for (Map<String, String> row : rows) {
            ClaimRecord record = toRecord(row, now);
            if (record == null) {
                log.warn("[11st] claim row without clmReqSeq/ordNo/ordPrdSeq/clmStat skipped: type={}", type);
                continue;
            }
            claimUpserter.upsert(account, type, record);
        }
    }

    /**
     * One 11st claim row → neutral record (D15 field mapping). Address / phone fields are never read (D43).
     * {@code received_at} is NOT NULL — an unreadable {@code reqDt} falls back to {@code now} (KST).
     *
     * @return null when the claim number, order number, line sequence or {@code clmStat} is missing
     */
    static ClaimRecord toRecord(Map<String, String> row, LocalDateTime now) {
        String claimNo = text(row, "clmReqSeq");
        String ordNo = text(row, "ordNo");
        String ordPrdSeq = text(row, "ordPrdSeq");
        String clmStat = text(row, "clmStat");
        if (claimNo == null || ordNo == null || ordPrdSeq == null || clmStat == null) {
            return null;
        }
        LocalDateTime receivedAt = ElevenstTimes.parse(text(row, "reqDt"));
        return new ClaimRecord(
                claimNo, ordNo, null, ordPrdSeq,
                text(row, "optName"),
                quantity(text(row, "clmReqQty")),
                status(clmStat), clmStat,
                null,
                text(row, "clmReqRsn"), text(row, "clmReqCont"),
                null, null,
                text(row, "twPrdInvcNo"), text(row, "dlvEtprsCd"),
                null, null, null, null,
                receivedAt != null ? receivedAt : now, null);
    }

    /** D25 status table; an unknown code stays open ({@code RECEIVED}), same as the Coupang claims. */
    static ClaimStatus status(String clmStat) {
        if (clmStat == null) {
            return ClaimStatus.RECEIVED;
        }
        return switch (clmStat) {
            case "105", "201" -> ClaimStatus.RECEIVED;
            case "103", "104", "109", "212", "214", "301" -> ClaimStatus.IN_PROGRESS;
            case "106", "221", "302" -> ClaimStatus.DONE;
            case "107", "232" -> ClaimStatus.REJECTED;
            case "108", "233" -> ClaimStatus.WITHDRAWN;
            default -> ClaimStatus.RECEIVED;
        };
    }

    private static int quantity(String value) {
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
}
