package com.pms.service.claim;

import com.pms.domain.OrderClaim;
import com.pms.service.coupang.SyncWindow;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 미완결 클레임의 접수일 범위를 조회 창으로 자른다 (FEATURE_2609_18 / D7·D10).
 * 반품(폭 = {@code claim-window-max-days})·교환(폭 = {@code exchange-window-days})이 공유한다.
 *
 * <p>⚠️ 폭과 상한은 <b>인자</b>다 — 여기서 설정을 읽으면 두 클레임 종류가 같은 폭을 강요당한다.
 * <p>⚠️ {@code ClaimType} 을 받지 않는다 — 넘겨받은 리스트만 보므로 이미 타입 무관이다.
 * <p>🔴 이 계산을 종류별로 복제하지 말 것 — "몇 일이 STALE 인가"·"폭이 며칠인가"가 두 벌로 갈린다.
 */
@Component
public class ClaimTrackingSlicer {

    /**
     * 미완결의 최소 접수일 ~ 오늘(KST) 을 {@code widthDays} 폭으로 자른다.
     *
     * 앞(오래된 쪽)부터 {@code maxSlices} 개까지만 만든다 — 잘리는 쪽은 항상 최신 구간이라
     * 신규 조회 창이 이미 덮는다(D10). 상한 0 = 슬라이스 조회 비활성.
     *
     * @param open      미완결 클레임(이미 STALE 스윕을 통과한 것)
     * @param widthDays 슬라이스 1개의 폭(일)
     * @param maxSlices 회차당 슬라이스 상한. 0 이하면 빈 리스트
     */
    public List<SyncWindow> slices(List<OrderClaim> open, int widthDays, int maxSlices) {
        // ⚠️ receivedAt 은 이미 KST 벽시계다 — atZone(...) 으로 다시 환산하면 9시간 밀린다.
        LocalDate oldest = open.stream()
                .map(OrderClaim::getReceivedAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .map(LocalDateTime::toLocalDate)
                .orElse(null);
        if (maxSlices <= 0 || oldest == null) {
            return List.of();
        }

        LocalDate today = LocalDate.now(SyncWindow.KST);
        List<SyncWindow> slices = new ArrayList<>();
        LocalDate from = oldest.isAfter(today) ? today : oldest;
        while (slices.size() < maxSlices) {
            LocalDate to = from.plusDays(widthDays);
            if (to.isAfter(today)) {
                to = today;
            }
            slices.add(new SyncWindow(from, to));
            if (!to.isBefore(today)) {
                break;
            }
            from = to.plusDays(1);
        }
        return slices;
    }
}
