package com.pms.service.external;

import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 외부연동 로그레벨을 런타임에 조회·토글하는 관리자 서비스.
 *
 * <p>Spring Boot {@link LoggingSystem}(자동 빈)을 우리 admin 엔드포인트로 감싼다(Actuator 미노출).
 * DEBUG 로 켜면 30분 후 자동으로 INFO 로 복귀시켜 로그 폭주를 막는다.
 *
 * <p>허용 레벨은 {@code DEBUG} / {@code INFO} 뿐이며, 대상은 {@link LoggingTarget} 화이트리스트로 제한된다.
 */
@Service
public class LoggingAdminService {

    /** select 목록 응답: {key, label}. */
    public record TargetSummary(String key, String label) {}

    /** 단건 상태 응답: 현재 레벨 + 자동복귀 예정시각(없으면 null). */
    public record TargetStatus(String target, String label, String level, Instant autoRevertAt) {}

    private static final long AUTO_REVERT_MINUTES = 30;

    private final LoggingSystem loggingSystem;

    /** DEBUG 로 켠 대상의 자동복귀 예정시각. INFO 로 되돌리면 제거된다. */
    private final Map<LoggingTarget, Instant> autoRevertAt = new ConcurrentHashMap<>();

    public LoggingAdminService(LoggingSystem loggingSystem) {
        this.loggingSystem = loggingSystem;
    }

    /** enum 전체를 select 목록으로 내린다(클라이언트 하드코딩 금지, 백엔드와 자동 동기화). */
    public List<TargetSummary> listTargets() {
        List<TargetSummary> result = new ArrayList<>();
        for (LoggingTarget t : LoggingTarget.values()) {
            result.add(new TargetSummary(t.name(), t.label()));
        }
        return result;
    }

    /** 대상의 현재 유효 로그레벨을 조회한다. */
    public TargetStatus get(LoggingTarget t) {
        String level = loggingSystem.getLoggerConfiguration(t.loggerName())
                .getEffectiveLevel().name();
        return new TargetStatus(t.name(), t.label(), level, autoRevertAt.get(t));
    }

    /**
     * 대상의 로그레벨을 설정한다. {@code DEBUG} 설정 시 30분 자동복귀를 예약하고, {@code INFO} 설정 시 해제한다.
     *
     * @param level {@code "DEBUG"} 또는 {@code "INFO"} 만 허용 (그 외 {@link IllegalArgumentException})
     */
    public TargetStatus set(LoggingTarget t, String level) {
        LogLevel logLevel = LogLevel.valueOf(level); // TRACE/WARN 등 → IllegalArgumentException
        if (logLevel != LogLevel.DEBUG && logLevel != LogLevel.INFO) {
            throw new IllegalArgumentException("Only DEBUG or INFO allowed: " + level);
        }
        loggingSystem.setLogLevel(t.loggerName(), logLevel);

        Instant revertAt = null;
        if (logLevel == LogLevel.DEBUG) {
            revertAt = Instant.now().plus(AUTO_REVERT_MINUTES, ChronoUnit.MINUTES);
            autoRevertAt.put(t, revertAt);
        } else {
            autoRevertAt.remove(t);
        }
        // Return the level we just set (getEffectiveLevel may not reflect it immediately).
        return new TargetStatus(t.name(), t.label(), logLevel.name(), revertAt);
    }

    /** 만료된 DEBUG 대상을 INFO 로 자동복귀시킨다(1분 주기 스윕). */
    @Scheduled(fixedRate = 60000)
    public void sweepExpired() {
        Instant now = Instant.now();
        autoRevertAt.forEach((target, revertAt) -> {
            if (!revertAt.isAfter(now)) {
                loggingSystem.setLogLevel(target.loggerName(), LogLevel.INFO);
                autoRevertAt.remove(target);
            }
        });
    }
}
