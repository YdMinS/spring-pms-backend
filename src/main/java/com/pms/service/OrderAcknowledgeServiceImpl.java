package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.dto.request.OrderAcknowledgeRequest;
import com.pms.repository.OrderItemRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangOrderStatus;
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
 * {@link OrderAcknowledgeService} 구현 — COUPANG 전용 발주처리 레그.
 *
 * 흐름: 라인 id dedupe → {@code findWithAccountByIdIn} 전개 → 라인 분류(비-쿠팡·박스 없음=unsupported,
 * 결제완료 아님=skipped) → 계정별 박스 dedupe → 계정별·청크별 1 PUT → responseList 집계 →
 * 성공 박스의 로컬 status 를 INSTRUCT 로 write-back(PLAN 2609_17 D3).
 *
 * ⚠️ 청크 단위 try/catch 로 전송 실패를 격리한다 — 실패한 청크의 박스만 failed 로 담고
 *    같은 계정의 다음 청크와 다른 계정 배치는 계속 보낸다.
 * ⚠️ 이 서비스에 {@code @Transactional} 을 붙이면 안 된다 — 외부 HTTP 를 도는 경로다.
 *    {@code OrderItem} 조회는 {@code @EntityGraph} finder 로만 한다(open-in-view=false).
 * ⚠️ 발주처리는 되돌릴 수 없다 — 자동 호출 금지(D4). 호출자는 컨트롤러 하나뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAcknowledgeServiceImpl implements OrderAcknowledgeService {

    private static final String PLATFORM_COUPANG = "COUPANG";

    /**
     * 전송 대상 상태 — 결제완료만.
     * ⚠️ 발송처리(PLAN 2609_07 D1)의 블랙리스트와 <b>방향이 반대</b>다. 거기는 "모르는 상태면 보낸다"
     *    (안 보내면 발송 누락), 여기는 "모르는 상태면 안 보낸다"(잘못 보내면 되돌릴 수 없다).
     *    상태는 되돌아오지 않으므로(단조성) 스킵으로 누락될 주문이 구조적으로 없다. PLAN 2609_17 D2.
     */
    private static final String STATUS_ACCEPT = CoupangOrderStatus.ACCEPT.name();
    private static final String STATUS_INSTRUCT = CoupangOrderStatus.INSTRUCT.name();

    /** 한 번에 보낼 박스 수 상한. 쿠팡 배열 상한 미확인분에 대한 보수적 기본값(PLAN 2609_17 D12). */
    private static final int CHUNK_SIZE = 50;

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderItemRepository orderItemRepository;
    private final ObjectMapper objectMapper;

    @Override
    public OrderAcknowledgeResult acknowledge(OrderAcknowledgeRequest request) {
        List<Long> ids = request.orderItemIds().stream().distinct().toList();
        List<OrderItem> lines = orderItemRepository.findWithAccountByIdIn(ids);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }

        List<String> unsupported = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        // skipped·unsupported 는 주문번호 단위다 — 옵션 3줄짜리 주문이 결과에 3번 나오지 않게 한 번만 담는다.
        // (status 는 첫 라인의 것 — ShipmentConfirmResult.SkippedOrder 와 같은 계약)
        Set<String> reportedOrders = new LinkedHashSet<>();
        Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        Map<Long, Set<String>> boxIdsByAccount = new LinkedHashMap<>();
        // 성공 시 status 를 갱신할 DB 라인(박스 단위).
        Map<String, List<OrderItem>> linesByBoxId = new LinkedHashMap<>();

        for (OrderItem line : lines) {
            MarketplaceAccount account = line.getMarketplaceAccount();
            String orderId = line.getExternalOrderId();
            String boxId = line.getExternalBoxId();
            // 플랫폼 판정은 계정 기준 — ShipmentConfirmServiceImpl 과 같은 기준이어야 두 레그가 갈라지지 않는다
            // (OrderItem 에도 platform 컬럼이 있지만 쓰지 않는다).
            if (!PLATFORM_COUPANG.equals(account.getPlatform()) || boxId == null || boxId.isBlank()) {
                if (reportedOrders.add(orderId)) {
                    unsupported.add(orderId);
                }
                continue;
            }
            if (!STATUS_ACCEPT.equals(line.getStatus())) {
                if (reportedOrders.add(orderId)) {
                    skipped.add(new SkippedOrder(orderId, line.getStatus()));
                }
                continue;
            }
            accountById.putIfAbsent(account.getId(), account);
            boxIdsByAccount.computeIfAbsent(account.getId(), k -> new LinkedHashSet<>()).add(boxId);
            linesByBoxId.computeIfAbsent(boxId, k -> new ArrayList<>()).add(line);
        }

        int targetBoxes = 0;
        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        List<String> succeededBoxIds = new ArrayList<>();

        for (Map.Entry<Long, Set<String>> entry : boxIdsByAccount.entrySet()) {
            MarketplaceAccount account = accountById.get(entry.getKey());
            List<String> boxIds = new ArrayList<>(entry.getValue());
            targetBoxes += boxIds.size();
            for (int from = 0; from < boxIds.size(); from += CHUNK_SIZE) {
                List<String> chunk = boxIds.subList(from, Math.min(from + CHUNK_SIZE, boxIds.size()));
                try {
                    AccountResult result = send(account, chunk);
                    succeeded += result.succeeded();
                    succeededBoxIds.addAll(result.succeededBoxIds());
                    failed.addAll(result.failed());
                } catch (Exception e) {
                    // 청크 격리: 이 청크의 박스 전체를 실패로. 같은 계정의 다음 청크는 계속 보낸다.
                    log.warn("발주처리 청크 실패: account={} boxes={}", account.getId(), chunk.size(), e);
                    for (String boxId : chunk) {
                        failed.add(new FailedBox(boxId, "ERROR", e.getMessage()));
                    }
                }
            }
        }

        markInstructed(succeededBoxIds, linesByBoxId);

        log.info("발주처리 결과: lines={} boxes={} succeeded={} failed={} skipped={} unsupported={}",
                lines.size(), targetBoxes, succeeded, failed.size(), skipped.size(), unsupported.size());

        return new OrderAcknowledgeResult(
                lines.size(), targetBoxes, succeeded, failed, skipped, unsupported);
    }

    /** 박스 id 청크 1개를 발주처리 API 로 보내고 응답을 집계. */
    private AccountResult send(MarketplaceAccount account, List<String> chunk) throws Exception {
        String path = coupangProperties.getAcknowledgementPath()
                .replace("{vendorId}", account.getVendorId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendorId", account.getVendorId());
        // external*Id 는 저장 시 String → 요청 바디는 long 으로 변환(발송처리와 같은 규칙).
        body.put("shipmentBoxIds", chunk.stream().map(Long::parseLong).toList());

        String response = coupangApiClient.put(path, objectMapper.writeValueAsString(body), account);
        // 실계정 미검증 스키마라 첫 dev 실행에서 원문을 눈으로 확인해야 한다(PII 없음: 박스 id·결과 코드뿐).
        log.debug("발주처리 응답 원문: account={} body={}", account.getId(), response);

        return parseResponse(response);
    }

    /** 응답 data.responseList 집계. data/responseList 없으면 예외(→ 청크 격리). */
    private AccountResult parseResponse(String json) {
        JsonNode data = readTree(json).path("data");
        if (data.isMissingNode() || data.path("responseList").isMissingNode()) {
            throw new IllegalStateException("쿠팡 발주처리 응답 파싱 실패");
        }
        int succeeded = 0;
        List<String> succeededBoxIds = new ArrayList<>();
        List<FailedBox> failed = new ArrayList<>();
        for (JsonNode r : data.path("responseList")) {
            if (r.path("succeed").asBoolean(false)) {
                succeeded++;                                    // 요약 숫자는 쿠팡 응답과 1:1
                String boxId = r.path("shipmentBoxId").asText("");
                if (!boxId.isBlank()) {
                    succeededBoxIds.add(boxId);
                }
            } else {
                failed.add(new FailedBox(
                        r.path("shipmentBoxId").asText(""),
                        r.path("resultCode").asText(""),
                        r.path("resultMessage").asText("")));
            }
        }
        return new AccountResult(succeeded, succeededBoxIds.stream().distinct().toList(), failed);
    }

    /**
     * 발주처리에 성공한 박스의 로컬 status 를 INSTRUCT 로 맞춘다(PLAN 2609_17 D3).
     *
     * <p>동기화를 기다리지 않고 송장 접수시트(status=INSTRUCT 고정 조회) 대상이 되어야 한다.
     * 다음 동기화가 쿠팡 값으로 덮어써도 같은 값이라 충돌하지 않는다.</p>
     *
     * ⚠️ 여기서 던지면 안 된다 — 쿠팡 전송은 이미 성공했고, 예외가 위 catch 에 걸리면 성공한 박스가
     *    failed 로 보고돼 사용자가 재전송한다(2609_07 D5 와 같은 이유). 본문 전체를 try 로 감싼다.
     */
    private void markInstructed(List<String> succeededBoxIds,
                                Map<String, List<OrderItem>> linesByBoxId) {
        try {
            List<OrderItem> updated = new ArrayList<>();
            for (String boxId : succeededBoxIds) {
                for (OrderItem line : linesByBoxId.getOrDefault(boxId, List.of())) {
                    updated.add(line.toBuilder().status(STATUS_INSTRUCT).build());
                }
            }
            if (!updated.isEmpty()) {
                orderItemRepository.saveAll(updated);
            }
        } catch (Exception e) {
            log.warn("발주처리 write-back 실패 (쿠팡 전송은 성공): {}", e.getMessage());
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 발주처리 응답 파싱 실패", e);
        }
    }

    /** 청크 1개의 전송 결과. */
    private record AccountResult(int succeeded, List<String> succeededBoxIds, List<FailedBox> failed) {
    }
}
