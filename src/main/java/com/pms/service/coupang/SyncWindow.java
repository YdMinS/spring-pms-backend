package com.pms.service.coupang;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 쿠팡 ordersheets 조회 창 (createdAt 기준, KST 달력).
 *
 * ⚠️ 쿠팡은 서버 TZ(UTC)가 아니라 <b>KST</b> 로 createdAt 을 필터한다 — 기본 창을 UTC 로 계산하면
 * KST 자정~오전 9시에 만들어진 주문이 전날로 밀려 창 밖으로 빠진다.
 * ⚠️ 범위는 31일 <b>미만</b>이어야 한다(쿠팡: "endTime-startTime range should less than 31").
 *
 * 창을 만드는 곳은 여기 하나다(FEATURE_2609_10 D6) — 조회 경로(CoupangOrderStatusSyncer)는 창을
 * 파라미터로만 받는다. 두 군데서 만들면 "어느 쪽이 진짜 기본인가"가 두 벌이 된다.
 */
public record SyncWindow(LocalDate from, LocalDate to) {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_RANGE_DAYS = 31;

    public SyncWindow {
        if (from == null || to == null) {
            throw new IllegalArgumentException("조회 기간(from, to)을 모두 지정해야 합니다.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("조회 기간은 31일 미만이어야 합니다. (쿠팡 API 제한)");
        }
    }

    /** 정기 동기화의 기본 창: 오늘(KST) − syncDays ~ 오늘(KST). */
    public static SyncWindow recent(int syncDays) {
        LocalDate to = LocalDate.now(KST);
        return new SyncWindow(to.minusDays(syncDays), to);
    }
}
