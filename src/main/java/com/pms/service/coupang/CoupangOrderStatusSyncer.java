package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.OrderItemUpserter.UpsertCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

/**
 * 쿠팡 ordersheets 를 상태 1개 단위로 조회·upsert 하는 커밋 경계.
 *
 * ⚠️ {@link #syncStatus} 가 <b>커밋 단위</b>다(PLAN D15). 상태 루프를 한 트랜잭션에 묶으면 뒤쪽 상태의
 * 쿠팡 실패가 앞쪽 상태의 upsert 까지 롤백시켜, 그 계정 주문이 DB 에 한 건도 남지 않는다(2026-09-02 사고).
 * ⚠️ 호출자({@link CoupangOrderSyncServiceImpl})는 트랜잭션 없이 호출해야 한다 — 바깥 트랜잭션이 있으면
 * REQUIRED 로 합류해 경계가 다시 하나로 합쳐진다.
 *
 * ⚠️ 외부 ID(orderId/shipmentBoxId/vendorItemId)는 모두 문자열로 저장한다(external_* = VARCHAR,
 *    옵션 매칭키 platform_item_id 와 타입 일치). 금액·배송정보는 저장하지 않고 raw 에 보존한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangOrderStatusSyncer {

    private static final int MAX_PER_PAGE = 50;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String KST_OFFSET = "%2B09:00";        // +09:00, URL-encoded (+ → %2B)

    private final CoupangApiClient coupangApiClient;
    private final OrderItemUpserter orderItemUpserter;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    /**
     * 상태 1개를 nextToken 이 빌 때까지 페이징 조회해 order_item 에 멱등 upsert 한다.
     *
     * 조회 창({@link SyncWindow})은 <b>호출자가 만들어 넘긴다</b>(FEATURE_2609_10 D6) — 정기 동기화는
     * 기본 창(오늘 − sync-days), 기간 백필은 사용자가 고른 창이다. 이 클래스는 창을 계산하지 않는다.
     *
     * account 는 detached 로 들어온다(파사드가 트랜잭션 밖에서 조회) — scalar 필드만 사용하므로 안전하다.
     * TenantContext 는 파사드가 같은 스레드에 세팅해 두므로 이 트랜잭션도 그 테넌트로 열린다.
     */
    @Transactional
    public StatusSyncResult syncStatus(MarketplaceAccount account, CoupangOrderStatus status, SyncWindow window) {
        String path = coupangProperties.getOrdersheetsPath().replace("{vendorId}", account.getVendorId());
        String baseQuery = baseQuery(status, window);
        String nextToken = null;

        int newCount = 0;
        int updatedCount = 0;
        int pages = 0;

        do {
            String query = (nextToken == null || nextToken.isBlank())
                    ? baseQuery
                    : baseQuery + "&nextToken=" + nextToken;

            JsonNode parsed = readTree(coupangApiClient.get(path, query, account));
            pages++;

            // 적재는 단일 진입점을 통한다(PLAN 2609_13 D1·D2) — 시트·폴백 경로와 같은 규칙으로 저장된다.
            // 이 호출은 syncStatus 의 트랜잭션에 REQUIRED 로 합류한다(의도된 동작 — 커밋 경계는 여전히 상태 1개, D15).
            UpsertCount counted = orderItemUpserter.upsertBoxes(account, parsed.path("data"));
            newCount += counted.newCount();
            updatedCount += counted.updatedCount();
            nextToken = parsed.path("nextToken").asText("");
        } while (nextToken != null && !nextToken.isBlank());

        return new StatusSyncResult(newCount, updatedCount, pages);
    }

    /**
     * 지정 창의 주문서 기본 쿼리 (지정 status, nextToken 제외).
     * 날짜는 ISO-8601 KST: "yyyy-MM-dd+09:00" (+ 는 %2B 로 인코딩, 서명/전송 동일 문자열 사용).
     */
    private String baseQuery(CoupangOrderStatus status, SyncWindow window) {
        return "createdAtFrom=" + window.from().format(DATE) + KST_OFFSET
                + "&createdAtTo=" + window.to().format(DATE) + KST_OFFSET
                + "&status=" + status.name()
                + "&maxPerPage=" + MAX_PER_PAGE;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 ordersheets 응답 파싱 실패", e);
        }
    }

    /** 상태 1개 처리 결과. */
    public record StatusSyncResult(int newCount, int updatedCount, int pages) {
    }
}
