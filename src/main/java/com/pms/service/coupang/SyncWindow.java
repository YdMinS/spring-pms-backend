package com.pms.service.coupang;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    /**
     * 종결 상태 전용 창 — 마지막 성공 동기화 이후만 덮는다(PLAN 2609_14 D3).
     *
     * 종결 상태는 되돌아오지 않으므로, 창은 "마지막 동기화 이후 경과 + 배송 소요"만 덮으면 충분하다.
     * 매일 돌면 minDays, 오래 쉬었으면 자동으로 넓어진다 — 고정 숫자를 튜닝할 필요가 없다.
     *
     * @param lastSuccess 마지막 ordersheets 성공 시각(null = 한 번도 성공 못 함)
     * @param minDays     하한. 자정 경계에서 하루가 통째로 비지 않게 하는 최소치
     * @param maxDays     상한. <b>호출자가 정한다</b> — 종결 상태 조회는 정기 창(sync-days),
     *                    클레임 조회는 claim-window-max-days(2609_18 D6, 하한보다 넓다)
     */
    public static SyncWindow recentSince(LocalDateTime lastSuccess, int minDays, int maxDays) {
        if (lastSuccess == null) {
            return recent(maxDays);          // 한 번도 성공 못 한 계정 = 현행 동작 그대로(D4)
        }
        // lastOrderSyncAt 은 서버 시각(UTC) naive 다 → UTC 로 해석한 뒤 KST 달력 날짜로 환산한다.
        // KST 로 바로 붙이면 9시간 최근으로 오독해 경과일이 하루 적게 나온다(놓치는 방향의 오차).
        LocalDate lastDate = lastSuccess.atZone(ZoneOffset.UTC).withZoneSameInstant(KST).toLocalDate();
        // 경과는 날짜끼리 뺀다 — 시각끼리 빼면 자정 직후에 0일이 나온다.
        long elapsed = ChronoUnit.DAYS.between(lastDate, LocalDate.now(KST));
        // min 이 바깥이라 minDays > maxDays 로 잘못 설정돼도 창이 상한을 넘지 못한다(D4).
        int days = (int) Math.min(maxDays, Math.max(minDays, elapsed + 1));
        return recent(days);
    }
}
