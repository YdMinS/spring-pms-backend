package com.pms.repository;

import com.pms.domain.MarketplaceAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarketplaceAccountRepository extends JpaRepository<MarketplaceAccount, Long> {

    List<MarketplaceAccount> findByIsActiveTrue();          // 동기화 대상(Phase 2~3)

    List<MarketplaceAccount> findBySeller_Id(Long sellerId);
}
