package com.pms.service.settlement.elevenst;

import com.pms.domain.ElevenstSettlementLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.repository.ElevenstSettlementLineRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
import com.pms.service.settlement.LocalPayoutBuilder;
import com.pms.service.settlement.SettlementPayoutUpserter.PayoutUpsertResult;
import com.pms.service.settlement.SettlementReconciler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 11st payouts = the account's stored 11st settlement lines grouped by remittance date ({@code stlPlnDy})
 * (FEATURE_2610_20 / D29 · D30 · D35). 11st has no payout API, so this is the only place 11st payouts come from.
 *
 * <ul>
 *   <li>One payout per (account, remittance date): type {@code DAILY} · payout date = remittance date ·
 *       recognition month = that date's month · totals = sums of its lines (refunds negative) · no adjustment rows.</li>
 *   <li>Recon status = {@link SettlementReconStatus#NO_TOTAL} — 11st gives no paid total to compare with (D13).</li>
 *   <li>D35: a payout that already exists keeps its status and confirmed payout date (the person's
 *       「지급 완료」 mark); only totals and lines are re-set.</li>
 *   <li>A line whose remittance date is missing is attached to no payout. A payout left with no line is deleted
 *       while still scheduled, and kept with zero totals once marked paid.</li>
 * </ul>
 *
 * <p>⚠️ {@code REQUIRES_NEW}: both callers loop over accounts without a transaction, same as
 * {@code SettlementPayoutUpserter}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElevenstPayoutBuilder implements LocalPayoutBuilder {

    private final ElevenstSettlementLineRepository elevenstSettlementLineRepository;
    private final SettlementPayoutRepository settlementPayoutRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementReconciler settlementReconciler;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PayoutUpsertResult rebuild(MarketplaceAccount account) {
        Map<LocalDate, List<SettlementLine>> groups = new TreeMap<>();
        List<SettlementLine> undated = new ArrayList<>();
        for (ElevenstSettlementLine mirror : elevenstSettlementLineRepository.findByMarketplaceAccount_Id(account.getId())) {
            if (mirror.getStlPlnDy() == null) {
                undated.add(mirror.getSettlementLine());
            } else {
                groups.computeIfAbsent(mirror.getStlPlnDy(), date -> new ArrayList<>()).add(mirror.getSettlementLine());
            }
        }

        Map<LocalDate, SettlementPayout> existing = new HashMap<>();
        for (SettlementPayout payout : settlementPayoutRepository.findByMarketplaceAccount_Id(account.getId())) {
            existing.put(payout.getSettlementDate(), payout);
        }

        List<SettlementLine> moved = new ArrayList<>();
        int attached = 0;
        for (Map.Entry<LocalDate, List<SettlementLine>> group : groups.entrySet()) {
            LocalDate date = group.getKey();
            List<SettlementLine> lines = group.getValue();
            SettlementPayout found = existing.remove(date);
            SettlementPayout.SettlementPayoutBuilder base = found != null ? found.toBuilder()
                    : SettlementPayout.builder()
                            .marketplaceAccount(account)
                            .revenueRecognitionMonth(YearMonth.from(date).toString())
                            .settlementType(SettlementType.DAILY)
                            .settlementDate(date)
                            .status(SettlementPayoutStatus.SCHEDULED);
            SettlementPayout payout = settlementPayoutRepository.save(base
                    .totalSale(signedSum(lines, SettlementLine::getSaleAmount, null))
                    .serviceFee(signedSum(lines, SettlementLine::getServiceFee, SettlementLine::getServiceFeeVat))
                    .finalAmount(settlementReconciler.lineTotal(lines))
                    .reconStatus(SettlementReconStatus.NO_TOTAL)
                    .build());
            for (SettlementLine line : lines) {
                SettlementPayout current = line.getSettlementPayout();
                if (current == null || !payout.getId().equals(current.getId())) {
                    moved.add(line.toBuilder().settlementPayout(payout).build());
                    attached++;
                }
            }
        }
        for (SettlementLine line : undated) {
            if (line.getSettlementPayout() != null) {
                moved.add(line.toBuilder().settlementPayout(null).build());
            }
        }
        if (!moved.isEmpty()) {
            settlementLineRepository.saveAll(moved);
        }

        for (SettlementPayout empty : existing.values()) {
            if (empty.getStatus() == SettlementPayoutStatus.PAID) {
                settlementPayoutRepository.save(empty.toBuilder()
                        .totalSale(BigDecimal.ZERO)
                        .serviceFee(BigDecimal.ZERO)
                        .finalAmount(BigDecimal.ZERO)
                        .build());
            } else {
                settlementPayoutRepository.delete(empty);
            }
        }

        log.info("11st payouts rebuilt: account={} payouts={} attached={} undated={} emptied={}",
                account.getId(), groups.size(), attached, undated.size(), existing.size());
        return new PayoutUpsertResult(groups.size(), attached, 0);
    }

    /** Σ of one or two amount columns over the lines — REFUND lines count negative (stored amounts are positive). */
    private static BigDecimal signedSum(List<SettlementLine> lines,
                                        Function<SettlementLine, BigDecimal> first,
                                        Function<SettlementLine, BigDecimal> second) {
        BigDecimal total = BigDecimal.ZERO;
        for (SettlementLine line : lines) {
            BigDecimal amount = nz(first.apply(line));
            if (second != null) {
                amount = amount.add(nz(second.apply(line)));
            }
            total = total.add(line.getSaleType() == SaleType.REFUND ? amount.negate() : amount);
        }
        return total;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
