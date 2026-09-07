package com.pms.repository;

import com.pms.domain.CoupangOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 쿠팡 주문 라인 extension 리포지토리 (FEATURE_2609_26 / PLAN D3·D19).
 *
 * <p>라인의 <b>자연키를 이 테이블이 소유</b>하므로 멱등 upsert 조회도 여기서 한다.
 */
public interface CoupangOrderLineRepository extends JpaRepository<CoupangOrderLine, Long> {

    /** UNIQUE 4키로 기존 라인 조회 — 동기화 upsert 의 멱등성 키. */
    Optional<CoupangOrderLine> findByMarketplaceAccount_IdAndShipmentBoxIdAndOrderIdRawAndVendorItemId(
            Long accountId, String shipmentBoxId, String orderIdRaw, String vendorItemId);

    /**
     * 3키 폴백 조회(계정 + orderId + vendorItemId) — 클레임 주문 매칭 2단계 (FEATURE_2609_18 / D22).
     *
     * <p>🔴 반환이 {@code List} 인 것이 핵심이다 — 합포장으로 같은 옵션이 여러 박스에 걸리면 2건 이상
     * 나오고, 그때는 틀린 라인에 붙이느니 미연결로 둔다(호출자가 판단). {@code Optional} 로 만들면
     * 2건일 때 {@code IncorrectResultSizeDataAccessException} 이 터진다.
     */
    List<CoupangOrderLine> findByMarketplaceAccount_IdAndOrderIdRawAndVendorItemId(
            Long accountId, String orderIdRaw, String vendorItemId);

    /** core 라인 id 로 거울 행 조회 — 금액 백필이 raw JSON 을 읽는 경로. */
    Optional<CoupangOrderLine> findByOrderLine_Id(Long orderLineId);
}
