package com.pms.service.inquiry;

import com.pms.dto.response.InquirySyncResponse;

/**
 * 채널 1개의 고객문의만 다시 가져오기 (2026-09-12).
 *
 * 문의 적재는 주문 동기화 안에도 붙어 있지만(2609_23 D12), 문의만 보려고 주문 전체를 돌릴 이유가
 * 없어서 같은 적재를 단독으로 부르는 입구를 따로 둔다. <b>적재 규칙은 한 벌</b>이다 —
 * 두 입구 모두 {@link InquirySyncAdapter#syncInquiries} 를 부르며, 여기서 적재를 다시 구현하지 않는다.
 *
 * <p>⚠️ 계정 단위다. 여러 채널을 도는 것은 화면이 한다 — 그래야 어느 채널을 조회 중인지·어느 채널이
 * 실패했는지 진행 상황을 그릴 수 있다(주문 동기화 화면과 같은 방식).
 */
public interface InquirySyncService {

    /**
     * 채널 1개의 문의를 가져온다.
     *
     * @param accountId 판매 계정(채널) id
     * @return 가져온 건수 요약
     * @throws com.pms.exception.ResourceNotFoundException 계정이 없으면
     * @throws IllegalArgumentException 문의 조회를 지원하지 않는 플랫폼이면(→ 400)
     */
    InquirySyncResponse sync(Long accountId);
}
