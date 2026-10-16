package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderAcknowledgeRequest;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.elevenst.ElevenstOrderActions;
import com.pms.service.elevenst.ElevenstRejectedException;
import com.pms.service.reservation.InternalShipmentStageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link OrderAcknowledgeService} implementation — the order-confirmation leg for COUPANG and ELEVENST
 * (FEATURE_2610_17 / D20).
 *
 * Flow: dedupe line ids → expand with {@code findWithAccountByIdIn} → classify lines (other platform · no box =
 * unsupported, not paid = skipped) → Coupang: dedupe boxes per account → one PUT per account and chunk → sum the
 * responseList; 11st: one 1634 call per (delivery number, product number) group (D48) → write PREPARING back to the
 * succeeded boxes / groups (PLAN 2609_17 D3).
 *
 * ⚠️ Send failures are isolated per chunk (Coupang) / per group (11st) — only those boxes go to failed and the next
 *    chunk, group and account are still sent.
 * ⚠️ Never put {@code @Transactional} on this service — it runs external HTTP.
 *    {@code OrderLine} is read only through {@code @EntityGraph} finders (open-in-view=false).
 * ⚠️ Order confirmation cannot be undone. There are only three callers — the controller (the screen's [발주처리]
 *    button) · <b>the run of a reservation the user made</b> (FEATURE_2609_75 / D6 — partly reverses 2609_17 D4) ·
 *    the internal-stage branch of [지금 발송] (D27). All three are paths where the user picked the orders.
 *    The order sync and other schedules still never call it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAcknowledgeServiceImpl implements OrderAcknowledgeService {

    /**
     * 전송 대상 상태 — 결제완료(PAID)만.
     * ⚠️ 발송처리(PLAN 2609_07 D1)의 블랙리스트와 <b>방향이 반대</b>다. 거기는 "모르는 상태면 보낸다"
     *    (안 보내면 발송 누락), 여기는 "모르는 상태면 안 보낸다"(잘못 보내면 되돌릴 수 없다).
     *    상태는 되돌아오지 않으므로(단조성) 스킵으로 누락될 주문이 구조적으로 없다. PLAN 2609_17 D2.
     */
    private static final OrderStatus STATUS_PAID = OrderStatus.PAID;
    /** 발주처리 성공 라인의 로컬 상태 — 화이트리스트와 <b>다른 지점</b>이다(PLAN 2609_17 D3). */
    private static final OrderStatus STATUS_PREPARING = OrderStatus.PREPARING;

    /** 한 번에 보낼 박스 수 상한. 쿠팡 배열 상한 미확인분에 대한 보수적 기본값(PLAN 2609_17 D12). */
    private static final int CHUNK_SIZE = 50;

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderLineRepository orderLineRepository;
    private final ObjectMapper objectMapper;
    private final InternalShipmentStageService internalShipmentStageService;
    /** FEATURE_2610_17 / D20 · D48: 11st order confirmation 1634. */
    private final ElevenstOrderActions elevenstOrderActions;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;

    @Override
    public OrderAcknowledgeResult acknowledge(OrderAcknowledgeRequest request) {
        List<Long> ids = request.orderItemIds().stream().distinct().toList();
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(ids);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }
        return send(lines, true).result();
    }

    @Override
    public ReservationAckResult acknowledgeForReservation(List<Long> orderLineIds) {
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(orderLineIds.stream().distinct().toList());
        if (lines.isEmpty()) {
            return new ReservationAckResult(List.of(), List.of());
        }
        Outcome outcome = send(lines, false);
        return new ReservationAckResult(outcome.succeededBoxIds(), outcome.result().failed());
    }

    /**
     * 라인 분류 → 계정·청크 전송 → PREPARING write-back.
     *
     * @param manual true = 화면 버튼 경로. 처리 중인 예약 발송 묶음이 섞이면 아무것도 보내지 않고 400(D18),
     *               성공 묶음의 내부 단계·예약 결과를 해제한다(D14). false = 예약 실행 경로(02).
     */
    private Outcome send(List<OrderLine> lines, boolean manual) {
        List<String> unsupported = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        // skipped·unsupported 는 주문번호 단위다 — 옵션 3줄짜리 주문이 결과에 3번 나오지 않게 한 번만 담는다.
        // (status 는 첫 라인의 것 — ShipmentConfirmResult.SkippedOrder 와 같은 계약)
        Set<String> reportedOrders = new LinkedHashSet<>();
        Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        Map<Long, Set<String>> boxIdsByAccount = new LinkedHashMap<>();
        // 성공 시 status 를 갱신할 DB 라인(박스 단위).
        Map<String, List<OrderLine>> linesByBoxId = new LinkedHashMap<>();
        // FEATURE_2610_17 / D48: 11st lines grouped by (account, delivery number, product number) — one 1634 call each.
        Map<String, List<OrderLine>> elevenstGroups = new LinkedHashMap<>();

        for (OrderLine line : lines) {
            MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
            String orderId = line.getOrder().getExternalOrderId();
            String boxId = (line.getOrderShipment() == null)
                    ? null : line.getOrderShipment().getExternalShipmentId();
            boolean supported = Platform.COUPANG.equals(account.getPlatform())
                    || Platform.ELEVENST.equals(account.getPlatform());
            // Platform judged by the account — the same basis as ShipmentConfirmServiceImpl so the two legs never
            // diverge (orders has a platform column too, but it is not used). COUPANG and ELEVENST (FEATURE_2610_17 / D20).
            if (!supported || boxId == null || boxId.isBlank()) {
                if (reportedOrders.add(orderId)) {
                    unsupported.add(orderId);
                }
                continue;
            }
            if (line.getStatus() != STATUS_PAID) {
                if (reportedOrders.add(orderId)) {
                    // D47: the acknowledge screen has no reason column → null.
                    skipped.add(new SkippedOrder(orderId,
                            line.getStatus() == null ? null : line.getStatus().name(), null));
                }
                continue;
            }
            if (Platform.ELEVENST.equals(account.getPlatform())) {
                elevenstGroups.computeIfAbsent(account.getId() + "|" + boxId, k -> new ArrayList<>()).add(line);
                continue;
            }
            accountById.putIfAbsent(account.getId(), account);
            boxIdsByAccount.computeIfAbsent(account.getId(), k -> new LinkedHashSet<>()).add(boxId);
            linesByBoxId.computeIfAbsent(boxId, k -> new ArrayList<>()).add(line);
        }

        if (manual) {
            // 처리 중인 예약 발송 묶음이 섞였으면 한 건도 보내지 않는다(D18 「처리 중」).
            internalShipmentStageService.assertNotRunning(shipmentIdsOf(linesByBoxId.keySet(), linesByBoxId));
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
        if (manual) {
            releaseInternalStage(succeededBoxIds, linesByBoxId);
        }

        // FEATURE_2610_17 / D57: the per-marketplace split — only marketplaces that had something to send.
        List<OrderAcknowledgeResult.PlatformResult> byPlatform = new ArrayList<>();
        if (targetBoxes > 0) {
            byPlatform.add(new OrderAcknowledgeResult.PlatformResult(Platform.COUPANG, succeeded, List.copyOf(failed)));
        }
        if (!elevenstGroups.isEmpty()) {
            ElevenstAckOutcome elevenst = acknowledgeElevenst(elevenstGroups);
            targetBoxes += elevenst.groups();
            succeeded += elevenst.succeeded();
            failed.addAll(elevenst.failed());
            if (elevenst.groups() > 0) {
                byPlatform.add(new OrderAcknowledgeResult.PlatformResult(Platform.ELEVENST, elevenst.succeeded(),
                        elevenst.failed()));
            }
        }

        log.info("발주처리 결과: lines={} boxes={} succeeded={} failed={} skipped={} unsupported={}",
                lines.size(), targetBoxes, succeeded, failed.size(), skipped.size(), unsupported.size());

        return new Outcome(new OrderAcknowledgeResult(lines.size(), targetBoxes, succeeded, failed, skipped,
                unsupported, List.copyOf(byPlatform)), List.copyOf(succeededBoxIds));
    }

    /**
     * 11st order confirmation (FEATURE_2610_17 / D20 · D48): within each delivery number, one 1634 call per product
     * number ({@code prdNo}); 11st confirms the other lines of that group with the same call, so every line of a
     * succeeded group becomes {@code PREPARING}. −3206 「이미 발주 처리 되었거나 발주 처리할 건이 없음」 counts as
     * success. A failed group is reported with its delivery number and the next group is still sent.
     *
     * @param linesByAccountAndDlvNo paid 11st lines keyed by {@code accountId|dlvNo}
     */
    private ElevenstAckOutcome acknowledgeElevenst(Map<String, List<OrderLine>> linesByAccountAndDlvNo) {
        List<Long> lineIds = linesByAccountAndDlvNo.values().stream().flatMap(List::stream)
                .map(OrderLine::getId).toList();
        Map<Long, ElevenstOrderLine> mirrors = elevenstOrderLineRepository.findByOrderLine_IdIn(lineIds).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        int groups = 0;
        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        List<OrderLine> confirmed = new ArrayList<>();
        for (List<OrderLine> dlvLines : linesByAccountAndDlvNo.values()) {
            MarketplaceAccount account = dlvLines.get(0).getOrder().getMarketplaceAccount();
            String dlvNo = dlvLines.get(0).getOrderShipment().getExternalShipmentId();
            Map<String, List<OrderLine>> byProduct = new LinkedHashMap<>();
            Map<String, ElevenstOrderLine> anchorByProduct = new HashMap<>();
            for (OrderLine line : dlvLines) {
                ElevenstOrderLine mirror = mirrors.get(line.getId());
                if (mirror == null) {
                    log.warn("11번가 발주처리 라인 제외 — 11번가 거울 행 없음: line={}", line.getId());
                    continue;
                }
                String prdNo = mirror.getPrdNo() == null ? "" : mirror.getPrdNo();
                byProduct.computeIfAbsent(prdNo, k -> new ArrayList<>()).add(line);
                anchorByProduct.putIfAbsent(prdNo, mirror);
            }
            for (Map.Entry<String, List<OrderLine>> group : byProduct.entrySet()) {
                groups++;
                try {
                    elevenstOrderActions.acknowledge(account, anchorByProduct.get(group.getKey()), dlvNo);
                    succeeded++;
                    confirmed.addAll(group.getValue());
                } catch (Exception e) {
                    log.warn("11번가 발주처리 실패: account={} dlvNo={} prdNo={}", account.getId(), dlvNo,
                            group.getKey(), e);
                    failed.add(failedGroup(dlvNo, e));
                }
            }
        }
        if (!confirmed.isEmpty()) {
            try {
                orderLineRepository.saveAll(confirmed.stream()
                        .map(line -> line.toBuilder().status(STATUS_PREPARING).build())
                        .toList());
            } catch (Exception e) {
                log.warn("11번가 발주처리 write-back 실패 (11번가 전송은 성공): {}", e.getMessage());
            }
        }
        return new ElevenstAckOutcome(groups, succeeded, failed);
    }

    /**
     * Failed 11st group row (D54): delivery number + 11st's {@code result_code} / {@code result_text} as given; a
     * call that never got a result (network, unreadable body) is {@code ERROR} + the exception text, like a Coupang
     * chunk failure.
     */
    private static FailedBox failedGroup(String dlvNo, Exception e) {
        if (e instanceof ElevenstRejectedException rejected && rejected.getResultCode() != null) {
            return new FailedBox(dlvNo, rejected.getResultCode(),
                    rejected.getResultText() == null ? "" : rejected.getResultText());
        }
        return new FailedBox(dlvNo, "ERROR", e.getMessage());
    }

    /** {@link #acknowledgeElevenst} result — groups sent · groups succeeded · failed groups. */
    private record ElevenstAckOutcome(int groups, int succeeded, List<FailedBox> failed) {
    }

    /** 박스 id → 그 박스 라인들의 배송 묶음 id. id 가 없는 묶음(저장 전)은 건너뛴다. */
    private Set<Long> shipmentIdsOf(Collection<String> boxIds, Map<String, List<OrderLine>> linesByBoxId) {
        Set<Long> shipmentIds = new LinkedHashSet<>();
        for (String boxId : boxIds) {
            for (OrderLine line : linesByBoxId.getOrDefault(boxId, List.of())) {
                if (line.getOrderShipment() != null && line.getOrderShipment().getId() != null) {
                    shipmentIds.add(line.getOrderShipment().getId());
                }
            }
        }
        return shipmentIds;
    }

    /**
     * 수동 발주처리에 성공한 박스의 내부 단계·예약 결과를 해제한다(D14).
     * ⚠️ 여기서 던지면 안 된다 — 쿠팡 전송은 이미 성공했다({@link #markInstructed} 와 같은 이유).
     */
    private void releaseInternalStage(List<String> succeededBoxIds, Map<String, List<OrderLine>> linesByBoxId) {
        try {
            Set<Long> shipmentIds = shipmentIdsOf(succeededBoxIds, linesByBoxId);
            if (!shipmentIds.isEmpty()) {
                internalShipmentStageService.clearAfterManualAcknowledge(shipmentIds);
            }
        } catch (Exception e) {
            log.warn("발주처리 후 내부 단계·예약 해제 실패 (쿠팡 전송은 성공): {}", e.getMessage());
        }
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

    /** {@link #send} 결과 — 화면 응답 + 성공 박스 id(예약 실행 경로가 쓴다, 02). */
    private record Outcome(OrderAcknowledgeResult result, List<String> succeededBoxIds) {
    }
}
