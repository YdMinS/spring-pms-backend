package com.pms.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 플랫폼 중립 고객문의 상태 (FEATURE_2609_23 / PLAN §3.1 · D7).
 *
 * 화면·필터는 이 정규화 값으로만 동작해 플랫폼이 늘어도 바뀌지 않는다. 원문 상태는 정보 손실 없이
 * {@link CustomerInquiry#getPlatformStatus()} 에 그대로 보존한다({@link ClaimStatus} 와 같은 자세).
 *
 * <p>{@code CLOSED} 는 고객센터 전용(상품문의에는 해당 값이 없다), {@code STALE} 은 로컬 전용이다(D9) —
 * 미답변이 {@code inquiry-stale-days} 를 넘기면 앵커(D8)가 무한히 뒤로 밀리는 것을 막기 위한
 * <b>강제 종결이지 삭제가 아니다</b>.
 */
public enum InquiryStatus {
    UNANSWERED,
    ANSWERED,
    CLOSED,
    STALE;

    /** 아직 우리가 답해야 하는 상태 = 앵커(D8)·STALE 스윕(D9)의 대상. */
    public boolean isOpen() {
        return this == UNANSWERED;
    }

    /**
     * 아직 우리가 답해야 하는 상태 집합 — 배지 카운트(FEATURE_2609_49 / D9)의 조회 인자.
     *
     * ⚠️ 목록을 손으로 나열하지 말 것. {@link #isOpen()} 에서 파생해야 상태가 늘어도 "무엇이 미답변인가"
     * 의 정의가 두 벌이 되지 않는다({@link ClaimStatus#closedStatuses()} 와 같은 자세).
     */
    public static List<InquiryStatus> openStatuses() {
        return Arrays.stream(values()).filter(InquiryStatus::isOpen).toList();
    }
}
