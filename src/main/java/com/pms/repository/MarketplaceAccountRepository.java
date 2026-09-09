package com.pms.repository;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MarketplaceAccountRepository extends JpaRepository<MarketplaceAccount, Long> {

    // seller 를 eager fetch: 송장 접수시트는 @Transactional 없이(외부 HTTP 루프) seller.sellerName 을
    // 접근하므로, open-in-view=false 환경에서 지연로딩 시 LazyInitializationException 이 난다.
    @EntityGraph(attributePaths = "seller")
    List<MarketplaceAccount> findByIsActiveTrue();          // 동기화 대상(Phase 2~3)

    List<MarketplaceAccount> findBySeller_Id(Long sellerId);

    @EntityGraph(attributePaths = "seller")
    List<MarketplaceAccount> findBySeller_IdAndIsActiveTrue(Long sellerId);  // 셀러 단위 동기화(Phase 3)

    // 채널 등록/승인 동기화(FEATURE_2608_06 / 3c): 셀의 (seller, platform) 계정 1건 해석.
    // (seller+platform 당 단일 계정 전제.)
    Optional<MarketplaceAccount> findBySeller_IdAndPlatform(Long sellerId, Platform platform);

    /**
     * 전 테넌트 id 목록 — {@code @TenantId} 필터를 우회하는 native 쿼리 (FEATURE_2609_30 / PLAN D11).
     *
     * <p>{@code @Scheduled} 정산 동기화는 SecurityContext 도 TenantContext 도 없어 일반 finder 가
     * NO_TENANT 로 0건이 된다. 스케줄러는 이 목록을 돌며 테넌트를 명시 set 한다(backend CLAUDE.md §9,
     * {@code OrderLineRepository.findDistinctTenantIds} 와 같은 장치).
     */
    @Query(value = "SELECT DISTINCT tenant_id FROM marketplace_account", nativeQuery = true)
    List<Long> findDistinctTenantIds();

    // 카테고리 조회(FEATURE_2608_06 / 45): sellerId 미지정 시 플랫폼의 임의 활성 계정 1건(HMAC 자격증명용).
    // @TenantId 로 현재 테넌트 자동 스코프.
    Optional<MarketplaceAccount> findFirstByPlatformAndIsActiveTrue(Platform platform);

    /**
     * 매출·정산 화면의 채널 축 — 판매자 필터만 받고 <b>비활성 계정도 포함</b>한다
     * (FEATURE_2609_30 / 03 ②).
     *
     * <p>⚠️ {@code findByIsActiveTrue} 를 재사용하지 않는 이유: 계정을 비활성으로 돌려도 그 채널로 판
     * 과거 매출과 아직 받지 못한 정산은 사라지지 않는다. 활성만 걸면 그 돈이 화면에서 조용히 증발한다.
     */
    @EntityGraph(attributePaths = "seller")
    @Query("select a from MarketplaceAccount a where (:sellerId is null or a.seller.id = :sellerId) "
            + "order by a.id asc")
    List<MarketplaceAccount> findAllWithSeller(@Param("sellerId") Long sellerId);
}
