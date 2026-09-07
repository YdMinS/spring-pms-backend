package com.pms.repository;

import com.pms.domain.ShoppingListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, Long> {

    /**
     * 자동 라인 upsert 키 (order_line × product).
     *
     * ⚠️ 파생 쿼리 이름은 <b>프로퍼티 경로</b>다 — 오타가 나면 컴파일이 아니라 컨텍스트 로딩에서 터진다.
     */
    Optional<ShoppingListItem> findByOrderLine_IdAndProduct_Id(Long orderLineId, Long productId);

    /** 수동 라인(order_line NULL) upsert 키 — product 당 1개 보장. */
    Optional<ShoppingListItem> findByOrderLineIsNullAndProduct_Id(Long productId);

    /**
     * 추출 전 주문 연결 라인의 autoQty 전체 리셋(=0). 출고/취소돼 더는 ACCEPT 가 아닌 주문 라인이
     * 재추출에서 갱신되지 않아 자연히 목록에서 빠지게 한다. 수동 라인(manualQty)·purchase_record 는 보존.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ShoppingListItem s SET s.autoQty = 0 WHERE s.orderLine IS NOT NULL")
    void resetAllAutoQty();
}
