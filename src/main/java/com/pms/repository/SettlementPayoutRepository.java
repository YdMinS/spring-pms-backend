package com.pms.repository;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.dto.response.PayoutAggregate;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 지급 묶음 리포지토리 (FEATURE_2609_30 / PLAN D5-2·D5-3).
 *
 * <p>🔴 유일키에 {@code settlementDate} 가 들어 있다 — 같은 인식월에 추가정산·유보금이 여러 건 오므로
 * (계정, 인식월, 유형)만으로 찾으면 서로 다른 입금 건이 하나로 뭉개진다.
 */
public interface SettlementPayoutRepository extends JpaRepository<SettlementPayout, Long> {

    /** 멱등 upsert 진입점 — 유일키 그대로. */
    Optional<SettlementPayout> findByMarketplaceAccount_IdAndRevenueRecognitionMonthAndSettlementTypeAndSettlementDate(
            Long marketplaceAccountId, String revenueRecognitionMonth, SettlementType settlementType,
            LocalDate settlementDate);

    /** 상세·리포트용 단건 — 채널/판매자 표기를 위해 계정·판매자를 함께 로딩한다(open-in-view=false). */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller"})
    Optional<SettlementPayout> findWithAccountById(Long id);

    /**
     * 목록 조회 — 판매자/채널/지급일 구간 필터. 파라미터가 null 이면 그 조건은 적용하지 않는다.
     *
     * <p>⚠️ 파생 메서드로 만들면 이름이 폭발하고 H2 파싱이 흔들린다(backend CLAUDE.md §5) — JPQL 로 둔다.
     */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller"})
    @Query("SELECT p FROM SettlementPayout p WHERE "
            + "(:sellerId IS NULL OR p.marketplaceAccount.seller.id = :sellerId) AND "
            + "(:accountId IS NULL OR p.marketplaceAccount.id = :accountId) AND "
            + "(:from IS NULL OR p.settlementDate >= :from) AND "
            + "(:to IS NULL OR p.settlementDate <= :to) "
            + "ORDER BY p.settlementDate DESC, p.id DESC")
    List<SettlementPayout> search(@Param("sellerId") Long sellerId,
                                  @Param("accountId") Long accountId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    /**
     * 그 계정·그 인식월의 지급 건 finalAmount 합 (FEATURE_2609_32 / PLAN 2609_32 D4·D6).
     *
     * <p>🔴 {@code settlementType} 으로 거르지 않는다 — 주정산+월정산+추가정산+유보금 <b>모든 유형의 합</b>이
     * 요점이다. 추가정산은 그 달 주정산에서 빠졌던 몫이라 월 단위로는 차이를 메우는 항목이다.
     *
     * <p>⚠️ {@code finalAmount} 미수신 건은 합에서 뺀다 — 0으로 더하면 차이가 항상 우리 쪽 초과로 나온다(D6).
     * 미수신 건수는 {@link #countByMarketplaceAccount_IdAndRevenueRecognitionMonthAndFinalAmountIsNull} 로
     * 따로 세어 화면이 "미수신 n건"을 밝힌다.
     */
    @Query("SELECT COALESCE(SUM(p.finalAmount), 0) FROM SettlementPayout p "
            + "WHERE p.marketplaceAccount.id = :accountId AND p.revenueRecognitionMonth = :month "
            + "AND p.finalAmount IS NOT NULL")
    BigDecimal sumFinalAmountByMonth(@Param("accountId") Long accountId, @Param("month") String month);

    /** 그 달 지급 건 수(전체 유형). */
    long countByMarketplaceAccount_IdAndRevenueRecognitionMonth(Long accountId, String month);

    /** 그 달 {@code finalAmount} 미수신 건 수 — 합계에서 빠진 건수다(D6). */
    long countByMarketplaceAccount_IdAndRevenueRecognitionMonthAndFinalAmountIsNull(Long accountId, String month);

    /** 계정 단위 존재 확인(적재 로그·테스트용). */
    List<SettlementPayout> findByMarketplaceAccountAndRevenueRecognitionMonth(
            MarketplaceAccount marketplaceAccount, String revenueRecognitionMonth);

    /**
     * 채널(계정)별 지급 묶음 집계 — 매출 화면의 "받을 돈" · 입금 확정 · 배지 (FEATURE_2609_30 / 03 ①②).
     *
     * <p>🔴 {@code pendingPayout} 에 <b>기간 조건이 없다</b>(D4). 지급 묶음의 축은 매출인식일이라 판매일
     * 기간과 겹치지 않는다 — 판매일 기간으로 자르면 "받을 돈"이 실제 받을 금액보다 작게 나온다.
     * 기간을 타는 것은 {@code paidAmount}(현금주의) 하나뿐이고, 기준일도 {@code finalSettlementDate} 다.
     *
     * <p>⚠️ {@code recon_status = AMOUNT_ONLY}(추가정산·유보금)를 제외하지 않는다 — 라인이 없을 뿐
     * 실제로 받을 돈이다(D5-4·D5-5).
     *
     * <p>⚠️ {@code case ... then p.finalAmount end}(else 없음)로 두는 이유: {@code else 0} 을 쓰면
     * 정수 리터럴과 DECIMAL 이 한 CASE 에 섞인다. 합계가 없으면 {@code coalesce} 가 0 으로 받는다.
     */
    @Query("""
            select new com.pms.dto.response.PayoutAggregate(
                a.id,
                coalesce(sum(case when p.status = :scheduled then p.finalAmount end), 0),
                coalesce(sum(case when p.status = :paid
                                   and p.finalSettlementDate >= :from
                                   and p.finalSettlementDate <= :to then p.finalAmount end), 0),
                sum(case when p.reconStatus = :unreconciled then 1 else 0 end),
                sum(case when p.reconStatus = :amountOnly then 1 else 0 end),
                count(p))
            from SettlementPayout p join p.marketplaceAccount a
            where (:sellerId is null or a.seller.id = :sellerId)
            group by a.id
            """)
    List<PayoutAggregate> aggregateByAccount(@Param("sellerId") Long sellerId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to,
                                             @Param("scheduled") SettlementPayoutStatus scheduled,
                                             @Param("paid") SettlementPayoutStatus paid,
                                             @Param("unreconciled") SettlementReconStatus unreconciled,
                                             @Param("amountOnly") SettlementReconStatus amountOnly);
}
