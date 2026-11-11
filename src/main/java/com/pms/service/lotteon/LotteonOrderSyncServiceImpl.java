package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.claim.ClaimSyncAdapter;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import com.pms.service.coupang.OrderSyncPreset;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.inquiry.InquirySyncAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link LotteonOrderSyncService} implementation (FEATURE_2611_23 / D11).
 *
 * <p>Orders: [209] outbound instructions over the last 14 days as 14 one-day windows, then [140] progress states over
 * the same 14 windows — one upserter call per window. The preset does not change the windows.
 *
 * <p>Cancels (FEATURE_2611_23 / 06 · R21 · R23): [50] once over the last 14 days, right after the orders; a failure is
 * a partial round (manual rounds record it) and is thrown on.
 *
 * <p>⚠️ No {@code @Transactional}: LotteON HTTP runs here; each upserter call commits on its own (the same reason
 * {@code ElevenstOrderSyncServiceImpl} has none).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteonOrderSyncServiceImpl implements LotteonOrderSyncService {

    /** D11: order lists look back this many days, one day per call. */
    static final int SYNC_DAYS = 14;

    private final LotteonOrderApi orderApi;
    private final LotteonOrderUpserter orderUpserter;
    private final LotteonCancelRequestUpserter cancelRequestUpserter;
    private final SyncStatusRecorder syncStatusRecorder;
    private final List<ClaimSyncAdapter> claimSyncAdapters;
    private final List<InquirySyncAdapter> inquirySyncAdapters;

    @Override
    public OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);

        UpsertCount orders;
        try {
            orders = loadOrders(account, now);
        } catch (RuntimeException e) {
            if (!scheduled) {
                syncStatusRecorder.recordFailure(account.getId(), e);
            }
            throw e;
        }

        int cancelUpdated;
        try {
            cancelUpdated = loadCancels(account, now);
        } catch (RuntimeException e) {
            // Orders landed but cancels did not: a cancelled line can still look purchasable → not a success (R23).
            if (!scheduled) {
                syncStatusRecorder.recordPartial(account.getId(),
                        "취소 반영 실패 — " + SyncStatusRecorder.summarize(e), true, false);
            }
            throw e;
        }

        try {
            claimSyncAdapters.stream()
                    .filter(a -> a.platform().equals(account.getPlatform()))
                    .findFirst()
                    .ifPresent(a -> {
                        a.syncExchanges(account);
                        syncStatusRecorder.recordClaimSyncCompleted(account.getId());
                    });
        } catch (Exception e) {
            // Returns / exchanges do not undo orders and cancels (same stance as the Coupang round).
            log.warn("[lotteon] claim sync failed (isolated): account={}", account.getId(), e);
        }

        try {
            inquirySyncAdapters.stream()
                    .filter(a -> a.platform().equals(account.getPlatform()))
                    .findFirst()
                    .ifPresent(a -> {
                        a.syncInquiries(account, preset == OrderSyncPreset.RECONCILE);
                        syncStatusRecorder.recordInquirySyncCompleted(account.getId());
                    });
        } catch (Exception e) {
            log.warn("[lotteon] inquiry sync failed (isolated): account={}", account.getId(), e);
        }

        // Success is recorded on scheduled rounds too — lastOrderSyncAt drives the screen banner.
        syncStatusRecorder.recordSuccess(account.getId());
        return new OrderSyncResult(LocalDateTime.now(), orders.newCount(), orders.updatedCount(), cancelUpdated, 0);
    }

    /** [209] over 14 one-day windows (oldest first), then [140] over the same windows; each window commits alone. */
    private UpsertCount loadOrders(MarketplaceAccount account, LocalDateTime now) {
        UpsertCount total = UpsertCount.empty();
        for (int back = SYNC_DAYS; back >= 1; back--) {
            LocalDateTime from = now.minusDays(back);
            LocalDateTime to = from.plusDays(1).minusSeconds(1);
            List<JsonNode> rows = orderApi.deliveryOrders(account, from, to);
            total = total.plus(orderUpserter.upsertRows(account, rows));
        }
        for (int back = SYNC_DAYS; back >= 1; back--) {
            LocalDateTime from = now.minusDays(back);
            LocalDateTime to = from.plusDays(1).minusSeconds(1);
            List<JsonNode> rows = orderApi.progressStates(account, from, to);
            total = total.plus(orderUpserter.upsertRows(account, rows));
        }
        log.info("[lotteon] orders loaded: account={} new={} updated={}",
                account.getId(), total.newCount(), total.updatedCount());
        return total;
    }

    /** [50] once over the last 14 days (R21) → order lines whose cancel quantity changed. */
    private int loadCancels(MarketplaceAccount account, LocalDateTime now) {
        return cancelRequestUpserter.upsertRows(account,
                orderApi.cancelRequests(account, now.minusDays(SYNC_DAYS), now));
    }
}
