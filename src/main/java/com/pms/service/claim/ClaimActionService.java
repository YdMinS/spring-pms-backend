package com.pms.service.claim;

import com.pms.domain.OrderClaim;
import com.pms.dto.request.ClaimActionRequest;
import com.pms.dto.response.ClaimActionResponse;

import java.util.List;
import java.util.Map;

/**
 * 클레임 처리 액션의 진입점 (FEATURE_2609_21 / 02).
 *
 * <p>가드(상태 화이트리스트 · 접수 단위 중복) → 어댑터 → 감사기록의 순서를 이 서비스가 소유한다.
 * 🔴 <b>호출자는 컨트롤러뿐이다</b>(D4) — 동기화 경로가 액션을 부르면 배치가 환불을 확정하는 사고가 난다.
 *
 * <p>{@link #availableActions}는 <b>판정의 유일한 진입점</b>이다(D1). 조회 서비스가 어댑터를 직접 들면
 * 어댑터 해석이 두 클래스에 복제돼 D17 이 절반만 지켜진다.
 */
public interface ClaimActionService {

    /**
     * 액션 1건 실행.
     *
     * @throws com.pms.exception.ResourceNotFoundException 없는 claim
     * @throws IllegalArgumentException                    현재 상태에서 불가 · 미지원 플랫폼 (→400)
     * @throws com.pms.exception.BusinessException         같은 접수에서 이미 성공한 액션 (→409)
     * @throws ClaimActionFailedException                  쿠팡이 실패를 돌려줌 (→502, 원문 포함)
     */
    ClaimActionResponse execute(Long claimId, ClaimActionRequest request);

    /**
     * 목록·상세 양쪽이 쓰는 유일한 계약 — claim id → 가능한 액션.
     *
     * <p>⚠️ claim 마다 형제 라인을 재조회하지 않는다. {@code getClaims} 는 페이지가 아니라 기간 전체
     * List 라서 N+1 이 그대로 조회 수가 된다 — 목록 크기와 무관하게 쿼리는 고정이다.
     * <p>액션은 ADMIN 전용이므로(D13) 비-ADMIN 에게는 전부 빈 목록으로 내린다.
     */
    Map<Long, List<ClaimActionOption>> availableActions(List<OrderClaim> claims);
}
