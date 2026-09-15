package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderRefreshRequest;
import com.pms.repository.OrderLineRepository;
import com.pms.service.coupang.OrderRefreshResult.FailedOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link OrderRefreshService} 구현 — COUPANG 전용 단건 발주서 조회 → 로컬 적재.
 *
 * 흐름: 라인 id dedupe → {@code findWithAccountByIdIn} 전개 → 주문번호 → 계정 맵 구성
 * (비-쿠팡 = unsupported) → 주문 수 상한 검사 → 주문마다 단건 조회 1회 → {@code upsertBoxes} 1회.
 *
 * ⚠️ <b>클래스 레벨 {@code @Transactional} 을 붙이지 않는다</b>(D5) — 외부 HTTP 를 도는 경로다.
 *    저장 경계는 {@link OrderUpserter#upsertBoxes} 가 주문 1건 단위로 소유한다.
 * ⚠️ 재시도·백오프·sleep·쿨다운을 만들지 말 것(D9) — 속도 제한과 429 는 {@code CoupangApiClientImpl}
 *    이 이미 건다(rateLimitGuard + callBudget 4/s).
 * ⚠️ {@code AccountSyncLock} 을 주입하지 않는다(D4). 락을 잡으면 전량 동기화가 도는 동안
 *    사용자 버튼이 계속 건너뜀으로 실패해 고장으로 보인다.
 * ⚠️ 행 펼치기·취소 라인 제외·상태 블랙리스트는 필요 없다 — 그건 "무엇을 전송할지" 규칙이고
 *    여기서는 전송이 없다. 갱신 대상 상태에도 제한을 두지 않는다(D10).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefreshServiceImpl implements OrderRefreshService {

    /**
     * 한 번에 조회할 주문 수 상한 — 단위는 <b>주문번호</b>다(D3).
     * 실제 비용은 주문 수다(라인 200개가 같은 주문 3개면 호출 3회). 50 × 0.25초(4/s) ≈ 13초.
     */
    private static final int MAX_ORDERS = 50;

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderLineRepository orderLineRepository;
    private final OrderUpserter orderUpserter;
    private final ObjectMapper objectMapper;

    @Override
    public OrderRefreshResult refresh(OrderRefreshRequest request) {
        long startedAt = System.currentTimeMillis();
        List<Long> ids = request.orderItemIds().stream().distinct().toList();
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(ids);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }

        List<String> unsupported = new ArrayList<>();
        Set<String> reportedOrders = new LinkedHashSet<>();
        // 주문번호 → 계정. 같은 주문번호는 한 번만 담긴다 = D1(조회 1회)의 본체.
        Map<String, MarketplaceAccount> accountByOrderId = new LinkedHashMap<>();

        for (OrderLine line : lines) {
            MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
            String orderId = line.getOrder().getExternalOrderId();
            // 플랫폼 판정은 계정 기준 — 다른 쿠팡 레그와 같은 기준(orders.platform 은 쓰지 않는다).
            if (!Platform.COUPANG.equals(account.getPlatform())) {
                if (reportedOrders.add(orderId)) {
                    unsupported.add(orderId);
                }
                continue;
            }
            accountByOrderId.putIfAbsent(orderId, account);
        }

        if (accountByOrderId.size() > MAX_ORDERS) {
            throw new IllegalArgumentException(
                    "한 번에 주문 " + MAX_ORDERS + "건까지 최신화할 수 있습니다 (선택: "
                            + accountByOrderId.size() + "건)");
        }

        int refreshed = 0;
        List<String> empty = new ArrayList<>();
        List<FailedOrder> failed = new ArrayList<>();

        for (Map.Entry<String, MarketplaceAccount> entry : accountByOrderId.entrySet()) {
            String orderId = entry.getKey();
            MarketplaceAccount account = entry.getValue();
            try {
                if (fetchAndUpsert(account, orderId)) {
                    refreshed++;
                } else {
                    // 0박스 = 전량 취소로 추정. 실패가 아니다(D7).
                    empty.add(orderId);
                }
            } catch (Exception e) {
                // 한 주문의 실패가 다음 주문을 막지 않는다(D8). 주문번호를 남긴다 — 문의 추적 단서다.
                log.warn("Order refresh failed: account={} orderId={}", account.getId(), orderId, e);
                failed.add(new FailedOrder(orderId, e.getMessage()));
            }
        }

        log.info("Order refresh: orders={} refreshed={} empty={} failed={} elapsedMs={}",
                accountByOrderId.size(), refreshed, empty.size(), failed.size(),
                System.currentTimeMillis() - startedAt);

        return new OrderRefreshResult(
                accountByOrderId.size(), refreshed, empty, failed, unsupported);
    }

    /**
     * 단건 발주서 조회 → 받은 박스 전부를 주문 3층에 적재.
     *
     * <p>상태·날짜 필터가 없다 — 쿠팡이 준 값을 그대로 반영하는 것이 최신화의 정의다(D10).
     *
     * <p>🔴 저장은 {@code upsertBoxes}(복수형) <b>1회</b>다 — 주문 1건이 커밋 단위라 박스 하나가
     * 깨지면 그 주문의 박스가 전부 롤백되고 주문은 failed 로 보고된다. "이 주문의 상태를 지금 값으로
     * 맞춘다"가 목적이므로 반쪽 갱신보다 통째 실패가 낫다(사용자가 다시 누르면 된다).
     *
     * @return 박스를 1개 이상 받았으면 true, 0박스면 false
     */
    private boolean fetchAndUpsert(MarketplaceAccount account, String orderId) {
        String path = coupangProperties.getOrdersheetByOrderPath()
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId())
                .replace("{orderId}", orderId);

        JsonNode parsed = readTree(coupangApiClient.get(path, "", account));
        JsonNode data = parsed.path("data");
        if (parsed.path("code").asInt(200) != 200 || !data.isArray()) {
            // 잘못된 orderId 에도 200 + 비정상 봉투가 올 수 있어 "0박스"와 "실패"를 여기서 분리한다.
            throw new IllegalStateException("쿠팡 발주서 단건 응답 이상: code=" + parsed.path("code").asText());
        }
        if (data.isEmpty()) {
            return false;
        }
        orderUpserter.upsertBoxes(account, data);
        return true;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 발주서 단건 응답 파싱 실패", e);
        }
    }
}
