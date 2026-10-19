package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.repository.SettlementPayoutRepository;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** {@link SettlementPaymentService} implementation (FEATURE_2610_20 / D34 · D38 · D39). */
@Service
@RequiredArgsConstructor
@Transactional
public class SettlementPaymentServiceImpl implements SettlementPaymentService {

    private final SettlementPayoutRepository settlementPayoutRepository;

    @Override
    public void markPaid(Long payoutId, LocalDate paidOn) {
        SettlementPayout payout = requireElevenst(payoutId);
        if (paidOn == null) {
            throw new IllegalArgumentException("실제 입금일을 골라 주세요.");
        }
        // D38 round 10: the arrival date never passes today (KST) — a call that skips the screen or a phone with a
        // wrong clock must not put "paid in the period" into a future month.
        if (paidOn.isAfter(LocalDate.now(SyncWindow.KST))) {
            throw new IllegalArgumentException("실제 입금일은 오늘 이후로 정할 수 없습니다.");
        }
        settlementPayoutRepository.save(payout.toBuilder()
                .status(SettlementPayoutStatus.PAID)
                .finalSettlementDate(paidOn)
                .build());
    }

    @Override
    public void unmarkPaid(Long payoutId) {
        SettlementPayout payout = requireElevenst(payoutId);
        settlementPayoutRepository.save(payout.toBuilder()
                .status(SettlementPayoutStatus.SCHEDULED)
                .finalSettlementDate(null)
                .build());
    }

    private SettlementPayout requireElevenst(Long payoutId) {
        SettlementPayout payout = settlementPayoutRepository.findWithAccountById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("지급 묶음을 찾을 수 없습니다: " + payoutId));
        MarketplaceAccount account = payout.getMarketplaceAccount();
        if (account == null || account.getPlatform() != Platform.ELEVENST) {
            throw new IllegalArgumentException("11번가 입금 건만 지급 완료를 표시할 수 있습니다.");
        }
        return payout;
    }
}
