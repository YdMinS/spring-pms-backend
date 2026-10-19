package com.pms.service.sales;

import com.pms.domain.FirstMonthState;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.Platform;
import com.pms.dto.response.FirstMonthQuestionResponse;
import com.pms.repository.MarketplaceAccountFixedCostRepository;
import com.pms.repository.SettlementLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * First-month (M0) judgement of the 11st server fee (FEATURE_2610_20 / D41 · D45 · D46).
 *
 * <p>M0 is charged by the previous month's 11st purchase-confirmed sales, which happened before the channel was
 * connected. After every 11st revenue load of an account ({@code SettlementSyncServiceImpl} — the 04:30 schedule,
 * [갱신] and [과거 정산 불러오기]) the links to an 11st catalog item go through these steps:</p>
 * <ol>
 *   <li>{@link #resolveAsking} — ASKING and the previous month now has lines → AUTO (D45 ③)</li>
 *   <li>{@link #startJudging} — not judged yet: M0 = the month of the earliest stored recognition date, written once
 *       (D45 ② ⑤); the caller then loads M0's previous month once</li>
 *   <li>{@link #finishJudging} — loaded with 1+ lines → AUTO, failed or 0 lines → ASKING (D45 ②)</li>
 * </ol>
 * <p>ANSWERED never changes (D45 ④). The charge itself is computed by {@link FixedCostCalculator} only.</p>
 *
 * <p>⚠️ Each step is its own transaction (REQUIRES_NEW): the caller is an outbound HTTP loop without one.</p>
 */
@Service
@RequiredArgsConstructor
public class ElevenstFirstMonthService {

    private final MarketplaceAccountFixedCostRepository fixedCostRepository;
    private final SettlementLineRepository settlementLineRepository;

    /** D45 ③: an ASKING link whose M0 previous month now has lines → AUTO (the question disappears). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveAsking(MarketplaceAccount account) {
        for (MarketplaceAccountFixedCost link : elevenstLinks(account)) {
            if (link.getFirstMonthState() != FirstMonthState.ASKING || link.getFirstMonth() == null) {
                continue;
            }
            YearMonth previous = YearMonth.parse(link.getFirstMonth()).minusMonths(1);
            if (settlementLineRepository.countByMarketplaceAccount_IdAndRecognitionDateBetween(
                    account.getId(), previous.atDay(1), previous.atEndOfMonth()) > 0) {
                fixedCostRepository.save(link.toBuilder().firstMonthState(FirstMonthState.AUTO).build());
            }
        }
    }

    /**
     * D45 ②: writes M0 on every not-judged link that has none yet (the month of the earliest stored recognition
     * date; nothing when the account has no line) and returns the distinct M0s whose previous month the caller must
     * load, earliest first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<YearMonth> startJudging(MarketplaceAccount account) {
        Set<YearMonth> months = new TreeSet<>();
        LocalDate earliest = null;
        boolean earliestRead = false;
        for (MarketplaceAccountFixedCost link : elevenstLinks(account)) {
            if (link.getFirstMonthState() != null) {
                continue;
            }
            String firstMonth = link.getFirstMonth();
            if (firstMonth == null) {
                if (!earliestRead) {
                    earliest = settlementLineRepository.findEarliestRecognitionDate(account.getId());
                    earliestRead = true;
                }
                if (earliest == null) {
                    continue;
                }
                firstMonth = YearMonth.from(earliest).toString();
                fixedCostRepository.save(link.toBuilder().firstMonth(firstMonth).build());
            }
            months.add(YearMonth.parse(firstMonth));
        }
        return List.copyOf(months);
    }

    /** D45 ②: the previous month of {@code firstMonth} was loaded with 1+ lines → AUTO, otherwise → ASKING. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishJudging(MarketplaceAccount account, YearMonth firstMonth, boolean loaded) {
        for (MarketplaceAccountFixedCost link : elevenstLinks(account)) {
            if (link.getFirstMonthState() == null && firstMonth.toString().equals(link.getFirstMonth())) {
                fixedCostRepository.save(link.toBuilder()
                        .firstMonthState(loaded ? FirstMonthState.AUTO : FirstMonthState.ASKING)
                        .build());
            }
        }
    }

    /** D45 ⑦: every ASKING link of the tenant, oldest link first. */
    @Transactional(readOnly = true)
    public List<FirstMonthQuestionResponse> questions() {
        return fixedCostRepository.findByFirstMonthStateOrderByIdAsc(FirstMonthState.ASKING).stream()
                .filter(link -> link.getFirstMonth() != null)
                .map(link -> {
                    MarketplaceAccount account = link.getMarketplaceAccount();
                    return new FirstMonthQuestionResponse(
                            link.getId(),
                            account.getId(),
                            account.getAccountAlias(),
                            account.getSeller() == null ? null : account.getSeller().getSellerName(),
                            link.getFirstMonth(),
                            YearMonth.parse(link.getFirstMonth()).minusMonths(1).toString());
                })
                .toList();
    }

    /**
     * D45 ⑦: [예] / [아니오] → ANSWERED + the answer. The answer decides M0 from now on, even when the previous
     * month's lines arrive later (D45 ④).
     *
     * @throws IllegalArgumentException unknown link · the link is not asking · {@code charged} missing (→ 400)
     */
    @Transactional
    public void answer(Long linkId, Boolean charged) {
        if (charged == null) {
            throw new IllegalArgumentException("예 또는 아니오를 골라 주세요.");
        }
        MarketplaceAccountFixedCost link = fixedCostRepository.findById(linkId)
                .orElseThrow(() -> new IllegalArgumentException("고정비 연결을 찾을 수 없습니다: " + linkId));
        if (link.getFirstMonthState() != FirstMonthState.ASKING) {
            throw new IllegalArgumentException("묻는 중인 연결이 아닙니다: " + linkId);
        }
        fixedCostRepository.save(link.toBuilder()
                .firstMonthState(FirstMonthState.ANSWERED)
                .firstMonthCharged(charged)
                .build());
    }

    /** The account's links to an 11st catalog item; nothing for a non-11st account. */
    private List<MarketplaceAccountFixedCost> elevenstLinks(MarketplaceAccount account) {
        if (account.getPlatform() != Platform.ELEVENST) {
            return List.of();
        }
        return fixedCostRepository.findByMarketplaceAccount_Id(account.getId()).stream()
                .filter(link -> link.getPlatformFixedCost() != null
                        && link.getPlatformFixedCost().getPlatform() == Platform.ELEVENST)
                .toList();
    }
}
