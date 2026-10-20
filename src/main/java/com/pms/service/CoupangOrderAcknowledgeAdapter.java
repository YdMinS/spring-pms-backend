package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coupang order confirmation (FEATURE_2610_23 / D21) — dedupe boxes per account → one PUT per account and chunk →
 * sum the responseList → write {@code PREPARING} back to the succeeded boxes (PLAN 2609_17 D3). Moved from
 * {@link OrderAcknowledgeServiceImpl} unchanged (D3). Coupang opens the internal-stage bundle (D23 · D24).
 *
 * <p>⚠️ Send failures are isolated per chunk — only those boxes go to failed and the next chunk and account are still
 * sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangOrderAcknowledgeAdapter implements OrderAcknowledgeAdapter {

    /** 발주처리 성공 라인의 로컬 상태 — 화이트리스트와 <b>다른 지점</b>이다(PLAN 2609_17 D3). */
    private static final OrderStatus STATUS_PREPARING = OrderStatus.PREPARING;

    /** 한 번에 보낼 박스 수 상한. 쿠팡 배열 상한 미확인분에 대한 보수적 기본값(PLAN 2609_17 D12). */
    private static final int CHUNK_SIZE = 50;

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderLineRepository orderLineRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    @Override
    public boolean internalStage() {
        return true;
    }

    @Override
    public AcknowledgeOutcome acknowledge(List<OrderLine> lines) {
        Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        Map<Long, Set<String>> boxIdsByAccount = new LinkedHashMap<>();
        // 성공 시 status 를 갱신할 DB 라인(박스 단위).
        Map<String, List<OrderLine>> linesByBoxId = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
            String boxId = line.getOrderShipment().getExternalShipmentId();
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
                    AccountResult result = sendChunk(account, chunk);
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
        return new AcknowledgeOutcome(targetBoxes, succeeded, List.copyOf(failed), List.of(),
                List.copyOf(succeededBoxIds));
    }

    /** 박스 id 청크 1개를 발주처리 API 로 보내고 응답을 집계. */
    private AccountResult sendChunk(MarketplaceAccount account, List<String> chunk) throws Exception {
        var cred = CoupangCredentials.of(account);
        String path = coupangProperties.getAcknowledgementPath()
                .replace("{vendorId}", cred.getVendorId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendorId", cred.getVendorId());
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
     * 발주처리에 성공한 박스의 로컬 상태를 {@code PREPARING} 로 맞춘다(PLAN 2609_17 D3).
     *
     * <p>🔴 화이트리스트({@code PAID})와 <b>다른 지점</b>이다 — 빠뜨리면 발주 성공 라인이 구 어휘로
     * 남아 송장 접수시트 대상에서 사라진다.
     *
     * <p>동기화를 기다리지 않고 송장 접수시트(status=INSTRUCT 고정 조회) 대상이 되어야 한다.
     * 다음 동기화가 쿠팡 값으로 덮어써도 같은 값이라 충돌하지 않는다.</p>
     *
     * ⚠️ 여기서 던지면 안 된다 — 쿠팡 전송은 이미 성공했고, 예외가 위 catch 에 걸리면 성공한 박스가
     *    failed 로 보고돼 사용자가 재전송한다(2609_07 D5 와 같은 이유). 본문 전체를 try 로 감싼다.
     */
    private void markInstructed(List<String> succeededBoxIds,
                                Map<String, List<OrderLine>> linesByBoxId) {
        try {
            List<OrderLine> updated = new ArrayList<>();
            for (String boxId : succeededBoxIds) {
                for (OrderLine line : linesByBoxId.getOrDefault(boxId, List.of())) {
                    updated.add(line.toBuilder().status(STATUS_PREPARING).build());
                }
            }
            if (!updated.isEmpty()) {
                orderLineRepository.saveAll(updated);
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
