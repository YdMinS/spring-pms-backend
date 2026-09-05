package com.pms.service.claim;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.dto.response.OrderClaimResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 적재된 order_claim 조회 (GET /api/claims — 화면 표시용 read).
 */
public interface ClaimQueryService {

    /**
     * 클레임 목록 (최신 접수순).
     *
     * @param type     null 이면 {@link ClaimType#RETURN}
     * @param status   null 이면 전체
     * @param sellerId null 이면 전체
     * @param from     접수일 시작(당일 포함). {@code to} 와 <b>함께</b> 주거나 둘 다 null
     * @param to       접수일 끝(<b>당일 포함</b> — 구현이 +1일 00:00 으로 변환)
     * @param keyword  주문번호·신청자명·상품명 부분일치. null 이면 전체
     * @throws IllegalArgumentException 하나만 주거나 from &gt; to (→ 400)
     *
     * 둘 다 null 이면 기본 창 = 오늘 − {@code coupang.sync-days} (주문 화면과 같은 창).
     * 적재 창은 {@code cancel-sync-days} 라 기본 창 앞부분이 비는 것은 정상이다.
     */
    List<OrderClaimResponse> getClaims(ClaimType type, ClaimStatus status, Long sellerId,
                                       LocalDate from, LocalDate to, String keyword);

    /**
     * 클레임 단건.
     *
     * @throws com.pms.exception.ResourceNotFoundException 없는 id (→ 404)
     */
    OrderClaimResponse getClaim(Long id);
}
