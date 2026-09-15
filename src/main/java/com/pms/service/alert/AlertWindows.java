package com.pms.service.alert;

import com.pms.config.CoupangProperties;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 알림의 조회 경계 계산 (FEATURE_2609_51 / PLAN D8). 피드(목록)와 요약(배지)이 <b>함께</b> 쓴다.
 *
 * <p>🔴 <b>기간을 두 곳에서 계산하지 말 것</b> — 목록과 배지가 다른 하한을 쓰면 "3건이라는데 2건만
 * 보인다"가 된다(D3 위반). 그래서 이 클래스가 유일한 출처다.
 *
 * <p>🔴 <b>설정값을 새로 만들지 않는다.</b> 클레임·문의의 기간은 새 제약이 아니라 이미 도는 규칙
 * ({@code ClaimStaleSweeper}·{@code InquiryStaleSweeper} 의 STALE 강제 종결)을 조회에 명시한 것이다.
 * 두 값이 갈라지면 알림에만 남는 유령 건이 생긴다.
 *
 * <p>⚠️ 컷오프는 전부 <b>한국시간 자정</b>({@code LocalDate.now(SyncWindow.KST)})이다 — 스윕이 그렇게
 * 자르고, 세 소스의 시각(주문 {@code paidAt}·클레임 {@code createdAt}·문의 {@code inquiryAt})이 전부
 * 마켓이 준 KST 벽시계인데 서버는 UTC naive 라, {@code LocalDateTime.now()} 를 쓰면 9시간 어긋난다
 * (이 프로젝트의 알려진 지뢰).
 */
@Component
@RequiredArgsConstructor
class AlertWindows {

    private final CoupangProperties coupangProperties;

    /** 새 주문 하한 — 구매목록과 <b>같은 창</b>(syncDays). 창 밖은 갱신되지 않는 stale 결제완료다. */
    LocalDateTime orderFrom() {
        return today().minusDays(coupangProperties.getSyncDays()).atStartOfDay();
    }

    /** 반품/교환 하한 — STALE 스윕과 같은 값(claimStaleDays). 평상시엔 아무것도 잘리지 않는다. */
    LocalDateTime claimFrom() {
        return today().minusDays(coupangProperties.getClaimStaleDays()).atStartOfDay();
    }

    /** 고객문의 하한 — STALE 스윕과 같은 값(inquiryStaleDays). */
    LocalDateTime inquiryFrom() {
        return today().minusDays(coupangProperties.getInquiryStaleDays()).atStartOfDay();
    }

    /**
     * 첫 장의 상한(커서 없음) — 🔴 <b>KST 기준 현재</b>다.
     *
     * <p>서버 UTC({@code LocalDateTime.now()})로 계산하면 최근 9시간에 들어온 건이 통째로 잘린다 —
     * 오늘 들어온 주문·반품·문의가 목록에 없는데 배지(상한 없음)만 올라가 D3 이 첫날부터 깨진다.
     */
    LocalDateTime nowUpperBound() {
        return LocalDateTime.now(SyncWindow.KST);
    }

    private LocalDate today() {
        return LocalDate.now(SyncWindow.KST);
    }
}
