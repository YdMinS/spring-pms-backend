package com.pms.controller;

import com.pms.domain.AlertType;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.AlertFeedResponse;
import com.pms.dto.response.AlertSummaryResponse;
import com.pms.service.alert.AlertFeedService;
import com.pms.service.alert.AlertSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 — 배지 카운트(FEATURE_2609_49 / D9) + 처리해야 할 일 목록(FEATURE_2609_51).
 *
 * <p>🔴 <b>카운트 창구는 하나로 유지한다.</b> 화면마다 자기 목록을 세지 말고 {@code /summary} 응답을
 * 쓴다. 항목이 늘면 {@link AlertSummaryResponse} 에 <b>필드를 추가</b>한다 — 카운트 엔드포인트를
 * 늘리지 말 것.
 *
 * <p>🔴 {@code GET /api/alerts}(목록)는 그 규칙의 예외가 아니다 — <b>목록은 모양이 달라 필드로 못 담는다</b>
 * (2609_51 D11). 카운트 창구는 여전히 {@code /summary} 하나다.
 *
 * <p>권한은 {@code anyRequest().authenticated()}({@link CustomerInquiryController} 와 동일) — 읽기 전용
 * 조회이고 담당자가 ADMIN 이 아닐 수 있으므로(2609_51 D15) {@code @PreAuthorize} 를 붙이지 않는다.
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertSummaryController {

    /** 목록 기본 크기 — 말풍선(20건)보다 넉넉하고 한 번에 읽는 양이 {@code 3 × size} 로 고정이다. */
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 200;

    private final AlertSummaryService alertSummaryService;
    private final AlertFeedService alertFeedService;

    /** 처리 대기 건수. 마켓을 치지 않고 로컬 DB 만 센다 — 화면이 짧은 주기로 불러도 안전하다. */
    @GetMapping("/summary")
    public ResponseEntity<ResponseDTO<AlertSummaryResponse>> summary() {
        return ResponseEntity.ok(ResponseDTO.success(alertSummaryService.summary()));
    }

    /**
     * 처리해야 할 일 목록 — 새 주문·반품/교환·문의를 합친 최신순 한 장.
     *
     * <p>⚠️ {@code cursor} 는 <b>불투명 문자열</b>이다: 클라이언트가 만들지 않고 이전 응답의
     * {@code nextCursor} 를 그대로 돌려보낸다. 형식이 깨졌으면 예외가 아니라 첫 장을 준다
     * (오래된 링크를 눌렀을 뿐이다). {@code nextCursor} 가 null 이면 끝이다.
     *
     * <p>{@code type} 은 {@link AlertType} 바인딩이라 모르는 값이면 Spring 이 400 을 낸다.
     */
    @GetMapping
    public ResponseEntity<ResponseDTO<AlertFeedResponse>> list(
            @RequestParam(required = false) AlertType type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(ResponseDTO.success(alertFeedService.feed(type, cursor, clamp(size))));
    }

    /** 잘못된 크기로 400 을 내지 않는다 — 훑어보는 목록이라 거절보다 보정이 낫다. */
    private int clamp(int size) {
        return Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, size));
    }
}
