package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.OrderItemUpserter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ClaimOrderBackfillService} 구현.
 *
 * ⚠️ <b>클래스 레벨 {@code @Transactional} 을 붙이지 않는다.</b> 이 서비스는 외부 HTTP 루프다 —
 * 트랜잭션을 열면 {@link OrderItemUpserter#upsertBox} 가 REQUIRED 로 합류해 루프 전체가 한 경계로
 * 묶인다(2026-09-02 사고와 동형). DB 쓰기는 전부 {@code OrderItemUpserter}·{@link ClaimUpserter} 의
 * 자기 트랜잭션에서 일어난다.
 *
 * ⚠️ 재시도·백오프·sleep 을 만들지 말 것 — 429 쿨다운은 {@code CoupangRateLimitGuard} 가 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimOrderBackfillServiceImpl implements ClaimOrderBackfillService {

    /** 미연결 클레임을 orderId 상한보다 넉넉히 뽑는 배수 — 한 orderId 에 클레임이 여러 건이라 dedupe 후에도 상한을 채워야 한다. */
    private static final int FETCH_MULTIPLIER = 10;

    private final OrderClaimRepository orderClaimRepository;
    private final CoupangApiClient coupangApiClient;
    private final OrderItemUpserter orderItemUpserter;
    private final ClaimUpserter claimUpserter;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    @Override
    public BackfillResult backfill(MarketplaceAccount account) {
        int maxOrders = coupangProperties.getClaimBackfillMaxOrders();
        if (maxOrders <= 0) {
            return new BackfillResult(0, 0);            // 비활성 스위치
        }

        int maxAttempts = coupangProperties.getClaimBackfillMaxAttempts();
        List<OrderClaim> unlinked = orderClaimRepository.findUnlinked(
                account.getId(), maxAttempts,
                PageRequest.of(0, maxOrders * FETCH_MULTIPLIER));

        // orderId 단위 dedupe — 한 주문의 반품 라인 여러 건은 조회 1회로 합쳐진다(D13).
        // 상한을 넘는 orderId 는 다음 회차가 가져간다(D10 과 같은 사고: 이월이지 실패가 아니다).
        Map<String, List<Long>> claimIdsByOrderId = new LinkedHashMap<>();
        for (OrderClaim claim : unlinked) {
            List<Long> ids = claimIdsByOrderId.get(claim.getExternalOrderId());
            if (ids == null) {
                if (claimIdsByOrderId.size() >= maxOrders) {
                    continue;                            // 상한 초과 orderId — 시도횟수도 소모하지 않는다
                }
                ids = new ArrayList<>();
                claimIdsByOrderId.put(claim.getExternalOrderId(), ids);
            }
            ids.add(claim.getId());
        }

        int ordersFetched = 0;
        int claimsLinked = 0;
        for (Map.Entry<String, List<Long>> entry : claimIdsByOrderId.entrySet()) {
            String orderId = entry.getKey();
            List<Long> claimIds = entry.getValue();
            int relinked = 0;                            // 이미 relink 를 탄 claim 수 — 실패 시 그쪽이 시도횟수를 올렸다
            try {
                fetchAndUpsert(account, orderId);
                ordersFetched++;
                for (Long claimId : claimIds) {
                    relinked++;
                    if (claimUpserter.relink(claimId)) {
                        claimsLinked++;
                    }
                }
            } catch (CoupangRateLimitedException e) {
                // 쿨다운 창(10분)에는 매 호출이 즉시 던진다 — 삼키면 남은 orderId 전부가 시도횟수를 1씩
                // 먹어 일시적 장애만으로 미연결 클레임이 영구 제외된다. D13 의 포기 조건은
                // "쿠팡에 없는 주문"이지 "지금 부를 수 없는 상태"가 아니다.
                throw e;
            } catch (Exception e) {
                // 한 주문의 실패가 회차를 끝내지 않는다. relink 를 이미 탄 claim 은 그쪽이 올렸으므로 제외.
                for (int i = relinked; i < claimIds.size(); i++) {
                    claimUpserter.recordMatchAttempt(claimIds.get(i));
                }
                log.warn("Claim order backfill failed for order: account={} orderId={}",
                        account.getId(), orderId, e);
            }
        }

        log.info("Claim order backfill done: account={} orders={} linked={}",
                account.getId(), ordersFetched, claimsLinked);
        return new BackfillResult(ordersFetched, claimsLinked);
    }

    /**
     * 단건 발주서 조회 → 모든 박스를 {@code order_item} 에 적재.
     *
     * <p>상태 필터가 없다 — 발송처리 폴백과 달리 여기서는 전송이 없고, 취소·배송완료 박스도 매칭 대상이다.</p>
     */
    private void fetchAndUpsert(MarketplaceAccount account, String orderId) {
        String path = coupangProperties.getOrdersheetByOrderPath()
                .replace("{vendorId}", account.getVendorId())
                .replace("{orderId}", orderId);

        JsonNode parsed = readTree(coupangApiClient.get(path, "", account));
        JsonNode data = parsed.path("data");
        if (parsed.path("code").asInt(200) != 200 || !data.isArray()) {
            // 잘못된 orderId 에도 200 + 비정상 봉투가 올 수 있어 "0박스"와 "실패"를 여기서 분리한다.
            throw new IllegalStateException("쿠팡 발주서 단건 응답 이상: code=" + parsed.path("code").asText());
        }
        for (JsonNode box : data) {
            orderItemUpserter.upsertBox(account, box);   // 박스마다 자기 트랜잭션 = 커밋 단위
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 발주서 단건 응답 파싱 실패", e);
        }
    }
}
