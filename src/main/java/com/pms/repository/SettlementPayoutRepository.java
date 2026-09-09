package com.pms.repository;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 계정 단위 존재 확인(적재 로그·테스트용). */
    List<SettlementPayout> findByMarketplaceAccountAndRevenueRecognitionMonth(
            MarketplaceAccount marketplaceAccount, String revenueRecognitionMonth);
}
