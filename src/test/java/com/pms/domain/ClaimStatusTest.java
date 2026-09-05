package com.pms.domain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿠팡 반품 receiptStatus 매핑 (2609_21/01) — 순수 단위 테스트(스프링·Mockito 없음).
 *
 * 응답의 실제값은 긴 코드인데 매핑이 요청 파라미터의 단축 코드로만 분기해 전 건이 RECEIVED 로
 * 저장되던 결함을 고정한다. 액션(02)의 가능 판정이 이 매핑과 platform_status 원문에 의존한다.
 */
class ClaimStatusTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(ClaimStatus.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void fromCoupangReturn_returnsCompleted_isDone() {
        assertThat(ClaimStatus.fromCoupangReturn("RETURNS_COMPLETED")).isEqualTo(ClaimStatus.DONE);
    }

    @Test
    void fromCoupangReturn_vendorWarehouseConfirm_isInProgress() {
        // 입고완료 = 승인 대기. 반품도 IN_PROGRESS 를 갖는다(더 이상 교환 전용이 아니다).
        assertThat(ClaimStatus.fromCoupangReturn("VENDOR_WAREHOUSE_CONFIRM")).isEqualTo(ClaimStatus.IN_PROGRESS);
    }

    @Test
    void fromCoupangReturn_requestCoupangCheck_isPendingReview() {
        assertThat(ClaimStatus.fromCoupangReturn("REQUEST_COUPANG_CHECK")).isEqualTo(ClaimStatus.PENDING_REVIEW);
    }

    @Test
    void fromCoupangReturn_uncheckedCodes_areReceived() {
        assertThat(ClaimStatus.fromCoupangReturn("RETURNS_UNCHECKED")).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(ClaimStatus.fromCoupangReturn("RELEASE_STOP_UNCHECKED")).isEqualTo(ClaimStatus.RECEIVED);
    }

    @Test
    void fromCoupangReturn_shortCodes_stayBackwardCompatible() {
        // 과거에 저장된 행이나 다른 조회 경로가 단축 코드를 줄 가능성을 배제할 수 없다.
        assertThat(ClaimStatus.fromCoupangReturn("CC")).isEqualTo(ClaimStatus.DONE);
        assertThat(ClaimStatus.fromCoupangReturn("PR")).isEqualTo(ClaimStatus.PENDING_REVIEW);
        assertThat(ClaimStatus.fromCoupangReturn("UC")).isEqualTo(ClaimStatus.RECEIVED);
    }

    @Test
    void fromCoupangReturn_unknownValue_isReceivedAndWarned_butNullIsSilent() {
        // default 분기가 조용히 삼키지 않는다는 것을 지키는 유일한 단언 — 문서에 없는 값이 오면 알아야 한다.
        assertThat(ClaimStatus.fromCoupangReturn("WHATEVER")).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(appender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("Unknown Coupang return receiptStatus")
                        && event.getFormattedMessage().contains("WHATEVER"));

        appender.list.clear();
        assertThat(ClaimStatus.fromCoupangReturn(null)).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(appender.list).isEmpty();      // null 은 경고 대상이 아니다(기존 가드)
    }
}
