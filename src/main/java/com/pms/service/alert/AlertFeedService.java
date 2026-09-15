package com.pms.service.alert;

import com.pms.domain.AlertType;
import com.pms.dto.response.AlertFeedResponse;

/**
 * 처리해야 할 일 목록 조회 (FEATURE_2609_51 / PLAN D1).
 *
 * <p>🔴 <b>읽기 전용이다</b> — 알림을 저장하지 않는다. 결제완료 주문·미완결 클레임·미답변 문의를
 * 조회 시점에 모아 만들고, 일이 끝나면(발주처리·클레임 종결·문의 답변) 다음 조회부터 저절로 사라진다.
 */
public interface AlertFeedService {

    /**
     * 처리해야 할 일 한 장. 최신순({@code occurredAt desc}) + 시각 커서 페이징(D12).
     *
     * @param alertType null 이면 전체(탭 필터). 지정하면 그 소스만 조회한다 — 버릴 것을 읽지 않는다
     * @param rawCursor 이전 응답의 {@code nextCursor}. null·형식 오류면 첫 장
     * @param pageSize  응답 최대 건수(컨트롤러가 1~200 으로 clamp). 읽는 양은 {@code 3 × pageSize} 로 고정
     */
    AlertFeedResponse feed(AlertType alertType, String rawCursor, int pageSize);
}
