package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 플랫폼별 정산 매출내역 조회 seam (FEATURE_2609_30 / PLAN D5).
 *
 * <p><b>필수 규칙</b>: 중립 적재 서비스({@link SettlementSyncService})는 이 인터페이스에만 의존한다 —
 * 쿠팡 경로·필드명·페이징 규칙을 중립 쪽으로 새어 나가게 하지 말 것(2609_26 원칙). 구현은 지금
 * {@code service/settlement/coupang/CoupangSettlementSource} 하나이며 주입은 {@code Map<Platform, ...>} 이다.
 *
 * <p>⚠️ 페이징·조회 창 분할(쿠팡 31일 상한)은 <b>구현 안에서</b> 처리한다. 호출자는 원하는 기간을 그냥
 * 넘기고, 400 을 사용자에게 그대로 보여주지 않는다.
 */
public interface SettlementSource {

    /** {@code marketplace_account.platform} 과 대조할 값. */
    Platform platform();

    /**
     * 이 계정의 [from, to] 매출인식일 구간 라인을 전부 읽어온다(페이징 내부 처리).
     *
     * <p>⚠️ 라인이 많으면 전부 메모리에 올라간다 — 적재 경로는 페이지 단위로 커밋해야 하므로
     * {@link #fetchRevenue(MarketplaceAccount, LocalDate, LocalDate, Consumer)} 를 쓴다.
     * 이 메서드는 테스트·단발 조회용 편의 형태다.
     */
    default List<SettlementLineDraft> fetchRevenue(MarketplaceAccount account, LocalDate from, LocalDate to) {
        List<SettlementLineDraft> all = new ArrayList<>();
        fetchRevenue(account, from, to, all::addAll);
        return all;
    }

    /**
     * 위와 같되 <b>페이지 1개를 읽을 때마다</b> 콜백한다 — 적재 서비스가 페이지 단위로 커밋하기 위한 형태다.
     *
     * <p>🔴 3페이지에서 실패해도 1~2페이지는 남아야 재실행이 싸다. 전량을 모아 한 번에 저장하면
     * 마지막 페이지의 실패가 앞의 성공을 전부 되돌린다.
     */
    void fetchRevenue(MarketplaceAccount account, LocalDate from, LocalDate to,
                      Consumer<List<SettlementLineDraft>> pageConsumer);
}
