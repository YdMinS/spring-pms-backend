package com.pms.service.cost;

import com.pms.domain.PurchaseRecord;
import com.pms.dto.response.CostDeviation;
import com.pms.dto.response.PropagationApplyResult;
import com.pms.dto.response.PropagationPreview;

import java.time.LocalDate;
import java.util.List;

/**
 * 원가 파급 (FEATURE_2609_28 / PLAN D4).
 *
 * <p>실매입가가 들어오면 상품 기준가를 최신으로 유지하고, 판매가는 <b>사람이 확정할 때</b> 다시 계산한다.
 *
 * <pre>
 * purchase_record.unit_price 입력  (reflect_to_base_price = true 일 때만)
 *   ① Product.price 갱신                      ← 자동            {@link #updateBasePrice}
 *   ② 옵션 원가 → 셀 sellingPrice 재계산       ← 사용자 트리거    {@link #preview} → {@link #apply}
 *   ③ 채널 push (쿠팡 가격변경)                ← 이 기능 밖. 기존 상품수정 경로에서 사람이 실행
 * </pre>
 *
 * <p>🔴 <b>자동은 ① 뿐이다</b>(PLAN D4). 원가가 틀리면 리포트만 틀리지만 <b>판매가가 틀리면 손해 보고
 * 팔거나 아예 안 팔린다.</b> ③ 을 자동 연쇄하면 단가 오타 하나가 즉시 실판매가로 나간다 — 쿠팡 가격변경은
 * 승인 불필요·즉시 반영이다.
 *
 * <p>⚠️ ① 만 자동이라 <b>원가는 최신인데 판매가는 낡은 구간</b>이 생긴다. 결함이 아니라 의도된 상태다
 * (PLAN D4-1). 그 구간을 드러내는 마진 경고는 추후 별도 기능이다.
 *
 * <p>⚠️ 판매자 축과의 관계: 매입은 (물품 × 판매자)지만 {@code Product.price} 는 판매자 축이 없는 전역
 * 값이다. 그래서 ① 은 <b>어느 판매자의 매입이든 마지막 값이 남는</b>다("항상 최신"의 의미). 판매자별
 * 실원가가 필요한 손익은 {@code Product.price} 가 아니라 {@code order_line.cost_basis} 스냅샷
 * ({@link CostBasisResolver})이 판매자 축으로 이미 담당한다 — 두 축은 서로 다른 질문에 답한다.
 *
 * @see CostBasisResolver 라인 원가 스냅샷(판매자 축)
 */
public interface CostPropagationService {

    /**
     * ① 매입 단가를 상품 기준가로 반영한다. {@code addPurchase} 성공 직후 호출된다.
     *
     * <p>조건은 {@link PurchaseRecord#movesBasePrice()} 하나가 소유한다(반영 여부·단가·수량).
     * 조건에 맞지 않으면 <b>아무것도 저장하지 않는다</b>.
     *
     * <p>⚠️ 여기서 파급(②)을 부르지 않는다. ① 은 즉시, ② 는 사용자가 확정할 때다 — 한 트랜잭션에 섞으면
     * 구매기록 저장이 파급 실패에 끌려 롤백된다.
     */
    void updateBasePrice(PurchaseRecord record);

    /**
     * ② dry-run. 최근 매입으로 기준가가 움직인 물품 → 마스터 → 셀을 세기만 한다(저장 없음).
     *
     * @param since 이 날짜 이후의 매입만 본다. null 이면 최근 7일
     */
    PropagationPreview preview(LocalDate since);

    /**
     * ② 확정 실행. 마스터마다 {@code MasterPropagationService.propagate} 를 그대로 부른다.
     *
     * <p>⚠️ 새 파급 엔진을 만들지 않는다 — 기존 서비스가 이미 BOM 동기화 + 옵션 원가·판매가 재계산 +
     * {@code needsMarketSync} 표시를 한다. 🔴 이 메서드는 <b>트랜잭션을 열지 않는다</b>(셀마다
     * {@code REQUIRES_NEW} 라는 기존 계약을 지키려면 부모 트랜잭션이 없어야 한다).
     */
    PropagationApplyResult apply(List<Long> masterIds);

    /**
     * 기준가와 최근 매입가의 괴리 목록(차이가 큰 순). <b>자동 갱신하지 않는다</b> — 사람이 판단할 재료다.
     */
    List<CostDeviation> deviations(int limit);
}
