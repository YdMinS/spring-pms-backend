package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 채널(계정)별 동기화 락 (FEATURE_2609_48 / D1·D2·D3).
 *
 * <p>🔴 <b>테넌트가 아니라 계정 단위다.</b> {@code syncEach} 는 한 요청 안에서 계정 4개를 동시에
 * 돌린다(2609_46 D6) — 테넌트로 묶으면 그 넷이 서로 기다려 병렬화를 스스로 죽인다. 계정 단위면
 * 한 요청이 같은 계정을 두 번 다루지 않으므로 자기를 막을 수 없다.
 *
 * <p>🔴 <b>기다리지 않는다.</b> 못 잡으면 즉시 실패다(D2). 대기시키면 웹 요청 스레드가 20초 물려
 * 게이트웨이 타임아웃이 난다.
 *
 * <p>🔴 <b>메모리이고, 재시작으로 사라지는 것이 맞다</b>(D3). 락이 지키는 건 "지금 돌고 있는 작업"
 * 이고 재시작이면 그 작업도 같이 죽었다. 락만 살아남으면 그게 고장이다.
 *
 * <p>🔴 <b>앱 인스턴스 1개 전제</b>(D11). 2개를 띄우면 락이 두 벌이 되어 무의미하다 —
 * {@link CoupangCallBudget}·{@link CoupangRateLimitGuard} 가 이미 같은 한계를 안고 있다.
 * 인스턴스를 늘릴 때는 셋을 따로 고치는 게 아니라 쿠팡을 치는 주체를 하나로 모으는 것이 답이다.
 *
 * <p>이 락은 <b>속도</b>를 다루지 않는다. 초당 상한은 {@link CoupangCallBudget} 담당이다.
 *
 * <p>만료 항목을 쓸어내는 스케줄러는 두지 않는다 — 해제 시 지워지고, 계정 수는 수십~수백이라
 * 맵이 의미 있게 자랄 수 없다({@link CoupangRateLimitGuard} 와 같은 판단).
 */
@Component
public class AccountSyncLock {

    /**
     * 열쇠의 절반. 지금은 주문 적재 하나뿐이다(D7).
     *
     * <p>문의·정산도 자기 자신에 대해 막고 싶어지면 여기 값만 추가한다 — 새 클래스를 만들지 말 것.
     */
    public enum SyncWork { ORDER }

    private record Key(SyncWork work, Long accountId) {}

    /** 값 = 잡은 시각. 회수 판정(D4)과 해제 시 주인 확인에 함께 쓴다. */
    private final Map<Key, Instant> holders = new ConcurrentHashMap<>();

    private final CoupangProperties coupangProperties;
    private final Clock clock;

    public AccountSyncLock(CoupangProperties coupangProperties, Clock clock) {
        this.coupangProperties = coupangProperties;
        this.clock = clock;
    }

    /**
     * 쥐고 있는 동안의 증표. {@code try (lease)} 로 닫는다 — 해제를 잊을 수 없게.
     *
     * <p>🔴 {@code remove(key, token)} 이다. 그냥 {@code remove(key)} 면, 회수당한(D4) 원래 주인이
     * 뒤늦게 끝나면서 <b>새 주인의 락을 풀어버린다.</b>
     */
    public final class Lease implements AutoCloseable {
        private final Key key;
        private final Instant token;

        private Lease(Key key, Instant token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            holders.remove(key, token);
        }
    }

    /**
     * 이 채널의 이 작업을 지금 시작해도 되는지.
     *
     * <p>🔴 판정과 점유가 한 번에 일어나야 한다 — "비었나 확인 → 넣기" 를 따로 하면 두 스레드가 둘 다
     * 통과한다. {@code computeIfAbsent} 로는 부족하다(회수 분기가 있다).
     *
     * @return 잡았으면 증표, 남이 쓰는 중이면 {@code null}
     */
    public Lease tryAcquire(SyncWork work, Long accountId) {
        Instant now = clock.instant();
        Duration maxHold = Duration.ofMinutes(coupangProperties.getSyncLockMaxHoldMinutes());
        AtomicReference<Instant> acquired = new AtomicReference<>();
        Key key = new Key(work, accountId);
        holders.compute(key, (k, heldSince) -> {
            if (heldSince == null || Duration.between(heldSince, now).compareTo(maxHold) >= 0) {
                acquired.set(now);        // 새로 잡거나, 오래 쥔 것을 뺏는다(D4)
                return now;
            }
            return heldSince;             // 남이 쓰는 중
        });
        Instant token = acquired.get();
        return token == null ? null : new Lease(key, token);
    }

    /** 현재 주인이 쥐고 있은 시간(초) — 로그 문구용. 비어 있으면 0. */
    public long heldSeconds(SyncWork work, Long accountId) {
        Instant heldSince = holders.get(new Key(work, accountId));
        return heldSince == null ? 0L : Duration.between(heldSince, clock.instant()).toSeconds();
    }
}
