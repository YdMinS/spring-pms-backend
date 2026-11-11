package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.CollectInvoiceSource;
import com.pms.domain.LotteonClaim;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.repository.LotteonClaimRepository;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.lotteon.LotteonOrderRows;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * LotteON return / exchange rows → {@code order_claim} with {@code platform = LOTTEON} + its {@code lotteon_claim} row
 * (FEATURE_2611_23 / D27 · D59 · D61 · R29 · R30). The 11st counterpart is {@link ElevenstClaimUpserter}.
 *
 * <ul>
 *   <li>Key = UNIQUE (account, type, {@code clmNo}, {@code odSeq}); {@code external_box_id} stays empty.</li>
 *   <li>Order line = the LotteON mirror (account, {@code odNo}, {@code odSeq}); linked once, then kept.</li>
 *   <li>{@code STALE} (local close) is not reopened by an open LotteON status.</li>
 *   <li>D85: a claim whose raw step is already 23 ~ 27 keeps its status and step when a list says 02 · 03 (the other
 *       fields are refreshed); list step 27 and withdrawals always overwrite.</li>
 *   <li>[140] rows ({@link #applyProgress}): retrieval lines move the step (23 ~ 27 only, never a {@code WITHDRAWN} /
 *       {@code STALE} claim) and fill the pickup invoice only when LotteON has one (2609_70 D13 · D68); the exchange
 *       re-ship line goes to {@code lotteon_claim} (D67 · R32). A [140] row never creates a claim.</li>
 *   <li>{@code hold_status} is written by the sync for returns only (D72).</li>
 * </ul>
 * ⚠️ {@code REQUIRES_NEW} on the sync methods: one row failing does not roll back the rest of the round. The
 * {@code mark…} methods are the commit unit of an action success (R36); callers hold no transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteonClaimUpserter {

    private static final Set<String> PROGRESS_STEPS = Set.of("23", "24", "25", "26", "27");
    private static final Set<String> LIST_EARLY_STEPS = Set.of("02", "03");

    private final OrderClaimRepository orderClaimRepository;
    private final LotteonClaimRepository lotteonClaimRepository;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;

    /**
     * One list row ([51] · [67] · [69] · [70]).
     *
     * @param holdStatus returns only: {@code "03"} when [51] {@code excpProcDvsCd} is 03, else null; ignored for
     *                   exchanges (D72)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(MarketplaceAccount account, ClaimType type, ClaimRecord record, String procSeq,
                       String orglProcSeq, String holdStatus) {
        Optional<OrderClaim> found = orderClaimRepository
                .findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                        account.getId(), type, record.externalClaimId(), record.externalItemId());
        LocalDateTime now = LocalDateTime.now();
        OrderClaim claim;
        Optional<LotteonClaim> lotteon;
        if (found.isEmpty()) {
            claim = orderClaimRepository.save(OrderClaim.builder()
                    .marketplaceAccount(account)
                    .platform(Platform.LOTTEON)
                    .claimType(type)
                    .externalClaimId(record.externalClaimId())
                    .externalOrderId(record.externalOrderId())
                    .externalItemId(record.externalItemId())
                    .orderLine(matchOrderLine(account, record))
                    .orderItemMatchAttempts(0)
                    .itemName(record.itemName())
                    .quantity(record.quantity())
                    .status(record.status())
                    .platformStatus(record.platformStatus())
                    .reasonCode(record.reasonCode())
                    .reasonText(record.reasonText())
                    .collectInvoiceNo(record.collectInvoiceNo())
                    .collectCarrierCode(record.collectCarrierCode())
                    .collectInvoiceSource(record.collectInvoiceNo() != null ? CollectInvoiceSource.PLATFORM : null)
                    .receivedAt(record.receivedAt())
                    .syncedAt(now)
                    .build());
            lotteon = Optional.empty();
        } else {
            OrderClaim existing = found.get();
            if (existing.getStatus() == ClaimStatus.STALE && record.status().isOpen()) {
                return;
            }
            // D85: a list's 02 · 03 does not rewind a claim that [140] already moved to 23 ~ 27.
            boolean keepStep = isProgressStep(existing.getPlatformStatus())
                    && LIST_EARLY_STEPS.contains(record.platformStatus())
                    && record.status() != ClaimStatus.WITHDRAWN;
            OrderLine orderLine = existing.getOrderLine() != null
                    ? existing.getOrderLine() : matchOrderLine(account, record);
            claim = orderClaimRepository.save(existing.toBuilder()
                    .orderLine(orderLine)
                    .itemName(record.itemName())
                    .quantity(record.quantity())
                    .status(keepStep ? existing.getStatus() : record.status())
                    .platformStatus(keepStep ? existing.getPlatformStatus() : record.platformStatus())
                    .reasonCode(record.reasonCode())
                    .reasonText(record.reasonText())
                    .collectInvoiceNo(record.collectInvoiceNo() != null
                            ? record.collectInvoiceNo() : existing.getCollectInvoiceNo())
                    .collectCarrierCode(record.collectCarrierCode() != null
                            ? record.collectCarrierCode() : existing.getCollectCarrierCode())
                    .collectInvoiceSource(record.collectInvoiceNo() != null
                            ? CollectInvoiceSource.PLATFORM : existing.getCollectInvoiceSource())
                    .syncedAt(now)
                    .build());
            lotteon = lotteonClaimRepository.findByOrderClaim_Id(existing.getId());
        }
        OrderClaim saved = claim;
        LotteonClaim row = lotteon.orElseGet(() -> LotteonClaim.builder().orderClaim(saved).build());
        LotteonClaim.LotteonClaimBuilder next = row.toBuilder()
                .procSeq(procSeq != null ? procSeq : row.getProcSeq())
                .orglProcSeq(orglProcSeq != null ? orglProcSeq : row.getOrglProcSeq());
        if (type == ClaimType.RETURN) {
            next.holdStatus(holdStatus);
        }
        lotteonClaimRepository.save(next.build());
    }

    /**
     * One [140] row (period window or D86 order lookup): a retrieval line ({@code RTRV}, {@code odTypCd} 40 · 30) or
     * an exchange re-ship line ({@code DV}, {@code odTypCd} 30) — anything else is skipped ({@link #isClaimRow}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyProgress(MarketplaceAccount account, JsonNode row) {
        if (isRetrievalRow(row)) {
            applyRetrieval(account, row);
        } else if (isReshipRow(row)) {
            applyReship(account, row);
        }
    }

    /** Hold (03) / release (09) success of [141] — every given claim's row (D80 · R36). */
    @Transactional
    public void markHold(List<Long> orderClaimIds, String holdStatus) {
        for (LotteonClaim row : lotteonClaimRepository.findByOrderClaim_IdIn(orderClaimIds)) {
            lotteonClaimRepository.save(row.toBuilder().holdStatus(holdStatus).build());
        }
    }

    /** [210] retrieval Y success — the next press does not send it again (R36). */
    @Transactional
    public void markRetrievalInformed(List<Long> orderClaimIds, LocalDateTime at) {
        for (LotteonClaim row : lotteonClaimRepository.findByOrderClaim_IdIn(orderClaimIds)) {
            lotteonClaimRepository.save(row.toBuilder().retrievalInformedAt(at).build());
        }
    }

    /** [210] re-ship delivery Y success (R36). */
    @Transactional
    public void markReshipInformed(List<Long> orderClaimIds, LocalDateTime at) {
        for (LotteonClaim row : lotteonClaimRepository.findByOrderClaim_IdIn(orderClaimIds)) {
            lotteonClaimRepository.save(row.toBuilder().reshipInformedAt(at).build());
        }
    }

    /** A [140] row this upserter reads: a claim retrieval line or an exchange re-ship line. */
    public static boolean isClaimRow(JsonNode row) {
        return isRetrievalRow(row) || isReshipRow(row);
    }

    private static boolean isRetrievalRow(JsonNode row) {
        String type = LotteonOrderRows.text(row, "odTypCd");
        return LotteonOrderRows.text(row, "clmNo") != null
                && "RTRV".equals(LotteonOrderRows.text(row, "dvRtrvDvsCd"))
                && ("40".equals(type) || "30".equals(type));
    }

    private static boolean isReshipRow(JsonNode row) {
        return LotteonOrderRows.text(row, "clmNo") != null
                && "DV".equals(LotteonOrderRows.text(row, "dvRtrvDvsCd"))
                && "30".equals(LotteonOrderRows.text(row, "odTypCd"));
    }

    /** R30 · D68: step (23 ~ 27 only) + pickup invoice (only when present) + process sequences. */
    private void applyRetrieval(MarketplaceAccount account, JsonNode row) {
        ClaimType type = "40".equals(LotteonOrderRows.text(row, "odTypCd")) ? ClaimType.RETURN : ClaimType.EXCHANGE;
        Optional<OrderClaim> found = orderClaimRepository
                .findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(account.getId(), type,
                        LotteonOrderRows.text(row, "clmNo"), LotteonOrderRows.text(row, "odSeq"));
        if (found.isEmpty()) {
            return;
        }
        OrderClaim claim = found.get();
        String step = LotteonOrderRows.text(row, "odPrgsStepCd");
        String invoice = LotteonOrderRows.text(row, "invcNo");
        OrderClaim.OrderClaimBuilder next = claim.toBuilder().syncedAt(LocalDateTime.now());
        if (invoice != null) {
            next.collectInvoiceNo(invoice)
                    .collectCarrierCode(LotteonOrderRows.text(row, "dvCoCd"))
                    .collectInvoiceSource(CollectInvoiceSource.PLATFORM);
        }
        if (isProgressStep(step) && claim.getStatus() != ClaimStatus.WITHDRAWN
                && claim.getStatus() != ClaimStatus.STALE) {
            next.status(LotteonClaimSyncAdapter.status(step)).platformStatus(step);
        }
        orderClaimRepository.save(next.build());

        LotteonClaim lotteon = lotteonClaimRepository.findByOrderClaim_Id(claim.getId())
                .orElseGet(() -> LotteonClaim.builder().orderClaim(claim).build());
        String procSeq = LotteonOrderRows.text(row, "procSeq");
        String orglProcSeq = LotteonOrderRows.text(row, "orglProcSeq");
        lotteonClaimRepository.save(lotteon.toBuilder()
                .procSeq(procSeq != null ? procSeq : lotteon.getProcSeq())
                .orglProcSeq(orglProcSeq != null ? orglProcSeq : lotteon.getOrglProcSeq())
                .build());
    }

    /** D67 · D68 · R32: the re-ship line of an exchange — same line first, else the only exchange of that claim. */
    private void applyReship(MarketplaceAccount account, JsonNode row) {
        String clmNo = LotteonOrderRows.text(row, "clmNo");
        String odSeq = LotteonOrderRows.text(row, "odSeq");
        Optional<OrderClaim> found = orderClaimRepository
                .findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(account.getId(),
                        ClaimType.EXCHANGE, clmNo, odSeq);
        if (found.isEmpty()) {
            List<OrderClaim> siblings = orderClaimRepository.findSiblings(account.getId(), ClaimType.EXCHANGE, clmNo);
            if (siblings.size() > 1) {
                log.warn("[lotteon] re-ship line matches {} exchange lines, skipped: account={} clmNo={}",
                        siblings.size(), account.getId(), clmNo);
            }
            if (siblings.size() != 1) {
                return;
            }
            found = Optional.of(siblings.get(0));
        }
        OrderClaim claim = found.get();
        String invoice = LotteonOrderRows.text(row, "invcNo");
        if (invoice != null) {
            orderClaimRepository.save(claim.toBuilder()
                    .reshipInvoiceNo(invoice)
                    .reshipCarrierCode(LotteonOrderRows.text(row, "dvCoCd"))
                    .syncedAt(LocalDateTime.now())
                    .build());
        }
        LotteonClaim lotteon = lotteonClaimRepository.findByOrderClaim_Id(claim.getId())
                .orElseGet(() -> LotteonClaim.builder().orderClaim(claim).build());
        lotteonClaimRepository.save(lotteon.toBuilder()
                .reshipOdSeq(odSeq)
                .reshipProcSeq(LotteonOrderRows.text(row, "procSeq"))
                .build());
    }

    private static boolean isProgressStep(String step) {
        return step != null && PROGRESS_STEPS.contains(step.trim());
    }

    private OrderLine matchOrderLine(MarketplaceAccount account, ClaimRecord record) {
        return lotteonOrderLineRepository
                .findByMarketplaceAccount_IdAndOdNoAndOdSeq(account.getId(), record.externalOrderId(),
                        record.externalItemId())
                .map(LotteonOrderLine::getOrderLine)
                .orElse(null);
    }
}
