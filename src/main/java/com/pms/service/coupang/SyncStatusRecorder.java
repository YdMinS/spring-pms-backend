package com.pms.service.coupang;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * 채널 동기화 결과 기록기 (FEATURE_2609_02 / PLAN D7·D18).
 *
 * ⚠️ 실제 쓰기는 {@link SyncStatusWriter} 가 {@code REQUIRES_NEW} 로 수행한다. 동기화 트랜잭션에
 * 합류하면 그 트랜잭션이 롤백될 때 "실패했다"는 기록까지 함께 사라져, 실패한 채널이 영원히
 * 무기록으로 남는다.
 *
 * ⚠️ 기록 실패가 동기화 자체를 깨면 안 된다. try/catch 를 {@code @Transactional} 메서드 <b>안</b>에
 * 두면 부족하다 — flush/commit 은 메서드가 반환된 뒤 프록시에서 일어나므로 그때 터진 예외는
 * 호출자로 그대로 빠져나간다. 그래서 try/catch 는 <b>프록시 바깥</b>인 아래 public 래퍼에 둔다.
 * (self-injection 대신 별도 빈 {@code SyncStatusWriter} 를 주입해 프록시 경유를 보장한다.)
 *
 * 기록 대상 계정은 {@code @TenantId} 로 자동 스코프되므로 호출 시점에 {@code TenantContext} 가
 * 세팅돼 있어야 한다({@link OrderSyncFacadeImpl#sync} 경로가 보장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncStatusRecorder {

    private static final int ERROR_MAX = 500;

    private final SyncStatusWriter writer;

    /** 전 상태 성공 + 취소 보정 성공. 기록 실패는 로그만 남기고 삼킨다(동기화는 계속). */
    public void recordSuccess(Long accountId) {
        try {
            writer.writeSuccess(accountId);
        } catch (Exception e) {
            log.warn("Sync status record failed: account={}", accountId, e);
        }
    }

    /**
     * 부분 성공(PLAN D18). 완료된 단계의 시각만 갱신한다 — 부분 성공한 단계는 "마지막 성공 시각"을
     * 흐리지 않도록 미변경으로 둔다. reason 문구는 호출자(파사드)가 확정한다.
     */
    public void recordPartial(Long accountId, String reason,
                              boolean orderSyncCompleted, boolean cancelSyncCompleted) {
        try {
            writer.writePartial(accountId, truncate(reason), orderSyncCompleted, cancelSyncCompleted);
        } catch (Exception e) {
            log.warn("Sync status record failed: account={}", accountId, e);
        }
    }

    /** 주문 조회 단계 전체 실패. */
    public void recordFailure(Long accountId, Throwable error) {
        try {
            writer.writeFailure(accountId, summarize(error));
        } catch (Exception e) {
            log.warn("Sync status record failed: account={}", accountId, e);
        }
    }

    /**
     * 예외 → 사용자에게 보여줄 한 줄 요약. <b>응답 바디는 담지 않는다</b>(PII·자격증명 유출 방지).
     *
     * 00 의 전 상태 실패는 {@code IllegalStateException: 쿠팡 주문 동기화 전 상태 실패: account=N} 로
     * 요약된다 — 원인 HTTP 코드는 이 예외에 없으므로 근본 원인은 00 이 남기는 상태별 WARN 로그로 추적한다.
     */
    public static String summarize(Throwable e) {
        if (e == null) {
            return null;
        }
        String summary = (e instanceof RestClientResponseException http)
                ? "HTTP " + http.getStatusCode().value() + " from Coupang"
                : e.getClass().getSimpleName() + ": " + e.getMessage();
        return truncate(summary);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= ERROR_MAX) {
            return value;
        }
        return value.substring(0, ERROR_MAX);
    }
}
