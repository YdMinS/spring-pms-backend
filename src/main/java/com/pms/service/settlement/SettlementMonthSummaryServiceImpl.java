package com.pms.service.settlement;

import com.pms.domain.SettlementPayoutStatus;
import com.pms.dto.response.PayoutMonthTotals;
import com.pms.dto.response.SettlementMonthSummaryResponse;
import com.pms.repository.SettlementPayoutRepository;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/** {@link SettlementMonthSummaryService} implementation (FEATURE_2610_29 D26 · D30). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementMonthSummaryServiceImpl implements SettlementMonthSummaryService {

    private final SettlementPayoutRepository settlementPayoutRepository;
    private final Clock clock;

    @Override
    public SettlementMonthSummaryResponse thisMonth() {
        YearMonth month = YearMonth.from(LocalDate.ofInstant(clock.instant(), SyncWindow.KST));
        PayoutMonthTotals totals = settlementPayoutRepository.sumByPayoutDate(
                month.atDay(1), month.atEndOfMonth(), SettlementPayoutStatus.PAID);
        BigDecimal total = scale(totals.total());
        BigDecimal paid = scale(totals.paid());
        return new SettlementMonthSummaryResponse(total, paid, total.subtract(paid));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
