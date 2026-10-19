package com.pms.repository;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
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

    /**
     * Single payout with its account and seller loaded (open-in-view=false) — for the detail · report channel and
     * seller display, and for the platform check of the 11st 「지급 완료」 mark (FEATURE_2610_20 / D34).
     */
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
     * <b>Revenue-recognition month</b> range lookup — the only path the sales screen uses to list "the settlements
     * for this period's sales" (FEATURE_2609_34).
     *
     * <p>🔴 A different axis from {@link #search}: that one is the <b>payout date</b> ({@code settlementDate} — the
     * scheduled date; for 11st the remittance date, and FEATURE_2610_20 / D38 keeps the list filter on it), this one
     * is the <b>revenue-recognition month</b> (which month's sales the settlement is for). The sales screen's period
     * is on the sale-date axis, so cutting by the payout date drops whole cases sold in August and paid in September
     * — do not merge the two methods.
     *
     * <p>⚠️ {@code revenue_recognition_month} is a fixed-width {@code 'YYYY-MM'} string, so lexicographic comparison
     * equals year-month comparison. That is why {@code >=}/{@code <=} are used as they are, and the index still
     * applies.
     *
     * <p>Order = recognition month descending (latest month on top) → within a month, payout date ascending (main
     * settlement 1st · 2nd · 3rd).
     */
    @EntityGraph(attributePaths = {"marketplaceAccount", "marketplaceAccount.seller"})
    @Query("SELECT p FROM SettlementPayout p WHERE "
            + "(:sellerId IS NULL OR p.marketplaceAccount.seller.id = :sellerId) AND "
            + "(:accountId IS NULL OR p.marketplaceAccount.id = :accountId) AND "
            + "p.revenueRecognitionMonth >= :fromMonth AND p.revenueRecognitionMonth <= :toMonth "
            + "ORDER BY p.revenueRecognitionMonth DESC, p.settlementDate ASC, p.id ASC")
    List<SettlementPayout> searchByRecognitionMonth(@Param("sellerId") Long sellerId,
                                                    @Param("accountId") Long accountId,
                                                    @Param("fromMonth") String fromMonth,
                                                    @Param("toMonth") String toMonth);

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

    /** Every payout of one account — the 11st remittance-date grouping reads them all (FEATURE_2610_20 / D29). */
    List<SettlementPayout> findByMarketplaceAccount_Id(Long marketplaceAccountId);

    /** 계정 단위 존재 확인(적재 로그·테스트용). */
    List<SettlementPayout> findByMarketplaceAccountAndRevenueRecognitionMonth(
            MarketplaceAccount marketplaceAccount, String revenueRecognitionMonth);

    /**
     * Payouts summed per channel (account) — the sales screen's "money to receive" · paid in the period
     * (FEATURE_2609_30 / 03 ①②).
     *
     * <p>🔴 {@code pendingPayout} has <b>no period condition</b> (D4). The payout axis is the revenue-recognition date,
     * which does not line up with the sale-date period — cutting by the sale period makes "money to receive" smaller
     * than what will actually be received. Only {@code paidAmount} (cash basis) takes the period.
     *
     * <p>🔴 The date {@code paidAmount} is cut by = the confirmed payout date when present, else {@code settlementDate}
     * (FEATURE_2610_20 / D38: an 11st payout marked paid carries the real arrival date there). <b>Never cut by
     * {@code finalSettlementDate} alone</b> (FEATURE_2609_34, checked against the docs 2026-09-11): the Coupang
     * payment-history API ({@code settlement-histories}) does <b>not send that field at all</b> — only the
     * revenue-history API ({@code revenue-history}) has it — so the column is NULL on every Coupang row in prod, and
     * cutting by it alone makes "paid in the period" <b>0 won forever</b> (measured). With the fallback, Coupang rows
     * are still cut by {@code settlementDate} + {@code status = DONE (PAID)}.
     *
     * <p>⚠️ {@code recon_status = AMOUNT_ONLY} (additional · reserve payouts) is not excluded — they carry no lines but
     * are money actually received (D5-4 · D5-5).
     *
     * <p>⚠️ Why {@code case ... then p.finalAmount end} (no else): {@code else 0} mixes an integer literal and DECIMAL
     * in one CASE. With no rows, {@code coalesce} turns it into 0.
     *
     * <p>🔴 <b>Do not count reconciliation statuses here</b> (FEATURE_2609_34). This aggregate has no period, so a count
     * from it on a row with a period filter reads as "N payouts mismatched in this period". Reconciliation status is
     * shown per payout by the recognition-month list {@link #searchByRecognitionMonth}.
     */
    @Query("""
            select new com.pms.dto.response.PayoutAggregate(
                a.id,
                coalesce(sum(case when p.status = :scheduled then p.finalAmount end), 0),
                coalesce(sum(case when p.status = :paid
                                   and coalesce(p.finalSettlementDate, p.settlementDate) >= :from
                                   and coalesce(p.finalSettlementDate, p.settlementDate) <= :to
                              then p.finalAmount end), 0))
            from SettlementPayout p join p.marketplaceAccount a
            where (:sellerId is null or a.seller.id = :sellerId)
            group by a.id
            """)
    List<PayoutAggregate> aggregateByAccount(@Param("sellerId") Long sellerId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to,
                                             @Param("scheduled") SettlementPayoutStatus scheduled,
                                             @Param("paid") SettlementPayoutStatus paid);
}
