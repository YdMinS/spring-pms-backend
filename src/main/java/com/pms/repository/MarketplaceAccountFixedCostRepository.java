package com.pms.repository;

import com.pms.domain.FirstMonthState;
import com.pms.domain.MarketplaceAccountFixedCost;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 채널 ↔ 고정비 항목 연결 (FEATURE_2609_33 / PLAN 2609_33 D1 · D2).
 */
public interface MarketplaceAccountFixedCostRepository
        extends JpaRepository<MarketplaceAccountFixedCost, Long> {

    /**
     * The links of one channel (settings screen · the replace's carried-over first month · the 11st first-month
     * judgement, FEATURE_2610_20 / D45). The catalog comes along because the amount and threshold are read.
     */
    @EntityGraph(attributePaths = {"platformFixedCost"})
    List<MarketplaceAccountFixedCost> findByMarketplaceAccount_Id(Long accountId);

    /** 카탈로그 DELETE 를 409 로 막는 판정 — 연결이 남아 있으면 지우지 못한다(끄려면 active=false). */
    boolean existsByPlatformFixedCost_Id(Long platformFixedCostId);

    /**
     * 매출 집계용 — 채널 전체의 연결을 <b>한 번에</b> 읽는다.
     *
     * <p>🔴 계정마다 부르면 채널 수만큼 쿼리가 나가고, 카탈로그를 같이 안 가져오면 금액을 읽는 순간
     * 다시 N+1 이 된다.
     */
    @EntityGraph(attributePaths = {"platformFixedCost"})
    List<MarketplaceAccountFixedCost> findByMarketplaceAccount_IdIn(Collection<Long> accountIds);

    /** Links in one first-month state with channel and seller — the 「정산 내역」 question bar (FEATURE_2610_20 / D45 ⑦). */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller"})
    List<MarketplaceAccountFixedCost> findByFirstMonthStateOrderByIdAsc(FirstMonthState firstMonthState);

    /** 멱등 replace(PUT) 의 전반부 — 지운 뒤 다시 넣는다. */
    void deleteByMarketplaceAccount_Id(Long accountId);
}
