package com.pms.repository;

import com.pms.domain.PurchaseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PurchaseRecordRepository extends JpaRepository<PurchaseRecord, Long> {

    /** 라인 묶음 단위로 구매 이력 일괄 조회 (조회 화면에서 itemId 별 SUM 집계용). */
    List<PurchaseRecord> findByItem_IdIn(Collection<Long> itemIds);
}
