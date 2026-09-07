package com.pms.repository;

import com.pms.domain.CoupangAccountCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 쿠팡 자격증명 리포지토리 (FEATURE_2609_26 / PLAN D15).
 *
 * <p>⚠️ 조회는 <b>계정 경유</b>만 노출한다. 자격증명 전량을 훑는 finder 를 추가하지 말 것 —
 * 읽기 경로는 {@code MarketplaceAccount.coupangCredential}(EAGER) 이고, 이 리포지토리는
 * 계정 CRUD 가 자격증명 행을 만들고 갱신하기 위한 통로다.
 */
public interface CoupangAccountCredentialRepository extends JpaRepository<CoupangAccountCredential, Long> {

    Optional<CoupangAccountCredential> findByMarketplaceAccountId(Long marketplaceAccountId);
}
