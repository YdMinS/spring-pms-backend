package com.pms.repository;

import com.pms.domain.MemberDashboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Dashboard row of a member (FEATURE_2610_29 D6). */
public interface MemberDashboardRepository extends JpaRepository<MemberDashboard, Long> {

    Optional<MemberDashboard> findByMember_Id(Long memberId);
}
