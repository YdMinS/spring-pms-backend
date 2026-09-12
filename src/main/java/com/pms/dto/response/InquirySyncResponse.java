package com.pms.dto.response;

import com.pms.service.inquiry.InquirySyncAdapter.InquirySyncResult;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 문의만 다시 가져오기 응답 (POST /api/inquiries/sync).
 *
 * ⚠️ {@link OrderSyncResponse} 에 합치지 말 것 — 주문 동기화 응답은 클라이언트 두 개가 쓰는 계약이라
 * 필드가 늘면 함께 움직여야 한다(문의 적재 결과가 주문 응답에 섞여서도 안 된다).
 *
 * <p>조회 슬라이스·페이지 수는 담지 않는다 — 그건 동기화 내부 사정이고 화면이 쓸 값이 아니다
 * (필요하면 서버 로그에 남는다).
 */
@Getter
@Builder
public class InquirySyncResponse {

    /** 어느 채널(판매 계정)을 가져왔는지. 화면이 채널별 진행을 그리는 데 쓴다. */
    private final Long accountId;

    /** 이번에 가져와 저장한 문의 건수(이미 있던 건을 다시 읽은 것도 포함). */
    private final int fetched;

    /** 오래 방치돼 이번 회차에 자동 종결한 미답변 문의 건수. */
    private final int staleClosed;

    private final LocalDateTime syncedAt;

    public static InquirySyncResponse of(Long accountId, InquirySyncResult result) {
        return InquirySyncResponse.builder()
                .accountId(accountId)
                .fetched(result.upserted())
                .staleClosed(result.staleClosed())
                .syncedAt(LocalDateTime.now())
                .build();
    }
}
