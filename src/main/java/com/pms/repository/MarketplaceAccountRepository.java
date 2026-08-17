package com.pms.repository;

import com.pms.domain.MarketplaceAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
    Optional<MarketplaceAccount> findBySeller_IdAndPlatform(Long sellerId, String platform);
}
