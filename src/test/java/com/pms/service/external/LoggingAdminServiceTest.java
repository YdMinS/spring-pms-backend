package com.pms.service.external;

import com.pms.service.external.LoggingAdminService.TargetStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * LoggingAdminService: set/자동복귀/스윕 핵심 로직 검증.
 */
@ExtendWith(MockitoExtension.class)
class LoggingAdminServiceTest {

    private static final String COUPANG_LOGGER = "com.pms.service.coupang";

    @Mock
    private LoggingSystem loggingSystem;

    @InjectMocks
    private LoggingAdminService service;

    @Test
    void set_DEBUG_자동복귀예약() {
        TargetStatus status = service.set(LoggingTarget.COUPANG, "DEBUG");

        verify(loggingSystem).setLogLevel(COUPANG_LOGGER, LogLevel.DEBUG);
        assertThat(status.level()).isEqualTo("DEBUG");
        assertThat(status.autoRevertAt()).isAfter(Instant.now());
    }

    @Test
    void set_INFO_자동복귀해제() {
        service.set(LoggingTarget.COUPANG, "DEBUG"); // arm auto-revert
        TargetStatus status = service.set(LoggingTarget.COUPANG, "INFO");

        assertThat(status.autoRevertAt()).isNull();
        // After INFO, get() reports no scheduled revert.
        given(loggingSystem.getLoggerConfiguration(COUPANG_LOGGER))
                .willReturn(new LoggerConfiguration(COUPANG_LOGGER, LogLevel.INFO, LogLevel.INFO));
        assertThat(service.get(LoggingTarget.COUPANG).autoRevertAt()).isNull();
    }

    @Test
    void set_잘못된레벨() {
        assertThatThrownBy(() -> service.set(LoggingTarget.COUPANG, "TRACE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 자동복귀스윕_만료분INFO복귀() throws Exception {
        // Arm DEBUG, then force the revert time into the past via reflection on the internal map.
        service.set(LoggingTarget.COUPANG, "DEBUG");
        var field = LoggingAdminService.class.getDeclaredField("autoRevertAt");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<LoggingTarget, Instant>) field.get(service);
        map.put(LoggingTarget.COUPANG, Instant.now().minus(1, ChronoUnit.MINUTES));

        service.sweepExpired();

        verify(loggingSystem).setLogLevel(COUPANG_LOGGER, LogLevel.INFO);
        assertThat(map).doesNotContainKey(LoggingTarget.COUPANG);
    }
}
