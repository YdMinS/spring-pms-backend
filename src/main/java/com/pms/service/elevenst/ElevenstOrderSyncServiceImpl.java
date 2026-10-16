package com.pms.service.elevenst;

import com.pms.domain.ElevenstCancelRequest;
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
import java.util.Map;

/**
 * {@link ElevenstOrderSyncService} implementation (FEATURE_2610_17 / D29 windows).
 *
 * <ul>
 *   <li>Orders: last 14 days as two 7-day windows (11st order lists accept 7 days at most) — QUICK = 1876 paid ·
 *       1635 preparing; RECONCILE adds 6897 shipping · 1727 delivered. The window ends now (KST).</li>
 *   <li>Cancels: 1639 · 1642 · 1724 over the last 14 days in one window.</li>
 * </ul>
 *
 * <p>⚠️ No {@code @Transactional}: 11st HTTP runs here; each upserter call commits on its own (the same reason
 * {@code OrderSyncFacadeImpl} has none).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElevenstOrderSyncServiceImpl implements ElevenstOrderSyncService {

    /** D29: order and claim lists look back this many days. */
    static final int SYNC_DAYS = 14;
    /** 11st order lists: at most 7 days per call. */
    static final int ORDER_WINDOW_DAYS = 7;

    private static final List<ElevenstOrderList> QUICK_LISTS =
            List.of(ElevenstOrderList.PAID, ElevenstOrderList.PREPARING);
    private static final List<ElevenstOrderList> RECONCILE_LISTS = List.of(ElevenstOrderList.values());

    private final ElevenstOrderApi orderApi;
    private final ElevenstOrderUpserter orderUpserter;
    private final ElevenstCancelRequestUpserter cancelRequestUpserter;
    private final SyncStatusRecorder syncStatusRecorder;
    private final List<ClaimSyncAdapter> claimSyncAdapters;
    private final List<InquirySyncAdapter> inquirySyncAdapters;

    @Override
    public OrderSyncResult sync(MarketplaceAccount account, OrderSyncPreset preset, boolean scheduled) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);

        UpsertCount orders;
        try {
            orders = loadOrders(account, preset, now);
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
            // Orders landed but cancels did not: a cancelled line can still look purchasable → not a success.
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
            log.warn("[11st] claim sync failed (isolated): account={}", account.getId(), e);
        }

        try {
            inquirySyncAdapters.stream()
                    .filter(a -> a.platform().equals(account.getPlatform()))
                    .findFirst()
                    .ifPresent(a -> {
                        a.syncInquiries(account);
                        syncStatusRecorder.recordInquirySyncCompleted(account.getId());
                    });
        } catch (Exception e) {
            log.warn("[11st] inquiry sync failed (isolated): account={}", account.getId(), e);
        }

        // Success is recorded on scheduled rounds too — lastOrderSyncAt drives the screen banner.
        syncStatusRecorder.recordSuccess(account.getId());
        return new OrderSyncResult(LocalDateTime.now(), orders.newCount(), orders.updatedCount(), cancelUpdated, 0);
    }

    /** Two 7-day windows per list, older window first; each list call commits on its own. */
    private UpsertCount loadOrders(MarketplaceAccount account, OrderSyncPreset preset, LocalDateTime now) {
        List<ElevenstOrderList> lists = (preset == OrderSyncPreset.RECONCILE) ? RECONCILE_LISTS : QUICK_LISTS;
        UpsertCount total = UpsertCount.empty();
        for (ElevenstOrderList list : lists) {
            for (int back = SYNC_DAYS; back > 0; back -= ORDER_WINDOW_DAYS) {
                LocalDateTime from = now.minusDays(back);
                LocalDateTime to = now.minusDays(back - ORDER_WINDOW_DAYS);
                List<Map<String, String>> rows = orderApi.listByMinute(account, list.path(), "ordPrdSeq", from, to);
                total = total.plus(orderUpserter.upsertRows(account, list, rows));
            }
        }
        log.info("[11st] orders loaded: account={} preset={} new={} updated={}",
                account.getId(), preset, total.newCount(), total.updatedCount());
        return total;
    }

    /** 1639 → 01 · 1642 → 02 · 1724 → 05, one 14-day window each (D29 · D40). */
    private int loadCancels(MarketplaceAccount account, LocalDateTime now) {
        LocalDateTime from = now.minusDays(SYNC_DAYS);
        int changed = 0;
        changed += cancelRequestUpserter.upsertRows(account,
                orderApi.listByMinute(account, ElevenstPaths.CANCEL_REQUESTS, "ordPrdCnSeq", from, now),
                ElevenstCancelRequest.STATUS_REQUESTED);
        changed += cancelRequestUpserter.upsertRows(account,
                orderApi.listByMinute(account, ElevenstPaths.CANCEL_COMPLETED, "ordPrdCnSeq", from, now),
                ElevenstCancelRequest.STATUS_COMPLETED);
        changed += cancelRequestUpserter.upsertRows(account,
                orderApi.listByMinute(account, ElevenstPaths.CANCEL_WITHDRAWN, "ordPrdCnSeq", from, now),
                ElevenstCancelRequest.STATUS_WITHDRAWN);
        return changed;
    }
}
