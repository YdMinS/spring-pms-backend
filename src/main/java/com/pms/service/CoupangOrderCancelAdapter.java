package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderCancelAction;
import com.pms.domain.OrderCancelReason;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderCancelActionRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.OrderCancelResult.CancelledLine;
import com.pms.service.OrderCancelResult.FailedLine;
import com.pms.service.OrderCancelResult.SkippedLine;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Coupang seller cancel (FEATURE_2609_25), moved from {@link OrderCancelServiceImpl} unchanged (FEATURE_2610_23 / D3 ·
 * D21).
 *
 * Flow: line sort (no box · no Coupang row = unsupported, status outside · fully cancelled = skipped, quantity over =
 * 400, no WING ID = failed) → group by account · order · box → one POST per group → {@code failedVendorItemIds}/
 * {@code receiptMap} judgement → write-back per receiptType → history rows.
 *
 * ⚠️ Send failures are isolated by a try/catch per group — one failing box does not stop the other boxes.
 * ⚠️ A failed write-back or history save does not flip the result — the Coupang cancel already happened, and
 *    reporting it as failed would make the user send it again (D7·D8).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangOrderCancelAdapter implements OrderCancelAdapter {

    /** 쿠팡 {@code bigCancelCode} 는 이 값 하나뿐이다(판매자 귀책 취소). */
    private static final String BIG_CANCEL_CODE = "CANERR";

    /** 즉시취소 접수 — 취소확정 수량으로 반영한다. 그 외(출고중지·미확인)는 보류 수량으로 간다(D7). */
    private static final String RECEIPT_TYPE_CANCEL = "CANCEL";

    private static final String NO_WING_ID = "NO_WING_ID";
    private static final String NO_WING_ID_MESSAGE =
            "판매채널 설정에 WING 로그인 ID를 등록해야 취소할 수 있습니다";

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderLineRepository orderLineRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final OrderCancelActionRepository orderCancelActionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    /** FEATURE_2610_17 / D71: a Coupang line needs {@code reason} (the DB no longer holds it NOT NULL — D70). */
    @Override
    public void checkReason(OrderCancelRequest request) {
        if (request.reason() == null) {
            throw new IllegalArgumentException(REASON_REQUIRED_MESSAGE);
        }
    }

    @Override
    public Batch begin(OrderCancelRequest request, List<OrderLine> lines) {
        // vendorItemId(전송 식별자)와 platform_status(이력의 근거)는 쿠팡 거울에 있다 — 한 번에 읽는다(04 §3-3).
        Map<Long, CoupangOrderLine> mirrors = coupangOrderLineRepository
                .findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        return new CoupangBatch(request.reason(), mirrors);
    }

    /** One request's Coupang lines. */
    private final class CoupangBatch implements Batch {

        private final OrderCancelReason reason;
        private final Map<Long, CoupangOrderLine> mirrors;
        private final List<Item> noWingId = new ArrayList<>();
        private final Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        private final Map<GroupKey, List<Item>> groups = new LinkedHashMap<>();

        private CoupangBatch(OrderCancelReason reason, Map<Long, CoupangOrderLine> mirrors) {
            this.reason = reason;
            this.mirrors = mirrors;
        }

        @Override
        public void add(OrderLine line, int quantity, List<SkippedLine> skipped, List<SkippedLine> unsupported) {
            MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
            String boxId = (line.getOrderShipment() == null)
                    ? null : line.getOrderShipment().getExternalShipmentId();
            CoupangOrderLine mirror = mirrors.get(line.getId());
            // 거울 행이 없으면 전송 식별자(vendorItemId)를 만들 수 없어 같은 부류로 묶는다.
            if (boxId == null || boxId.isBlank() || mirror == null) {
                unsupported.add(OrderCancelAdapter.skippedLine(line, UNSUPPORTED_MESSAGE));
                return;
            }
            if (line.getStatus() == null || !CANCELLABLE_STATUSES.contains(line.getStatus())) {
                skipped.add(OrderCancelAdapter.skippedLine(line, "취소할 수 없는 상태입니다"));
                return;
            }
            if (line.isFullyCancelled()) {
                skipped.add(OrderCancelAdapter.skippedLine(line, "이미 전량 취소된 주문입니다"));
                return;
            }
            if (quantity > line.purchasableQty()) {
                // 되돌릴 수 없는 API 다 — 왕복 전에 전체 요청을 막는다(D3, 부분 전송 금지).
                throw new IllegalArgumentException(
                        "취소 수량이 취소 가능 수량(" + line.purchasableQty() + "개)을 초과했습니다");
            }
            String wingUserId = CoupangCredentials.of(account).getVendorUserId();
            if (wingUserId == null || wingUserId.isBlank()) {
                // WING ID 는 API 필수값이라 없으면 400 이 확정 — 왕복하지 않는다(D10).
                noWingId.add(new Item(line, mirror, quantity));
                return;
            }
            accountById.putIfAbsent(account.getId(), account);
            groups.computeIfAbsent(
                    new GroupKey(account.getId(), line.getOrder().getExternalOrderId(), boxId),
                    k -> new ArrayList<>()).add(new Item(line, mirror, quantity));
        }

        @Override
        public void send(List<CancelledLine> cancelled, List<FailedLine> failed) {
            for (Item item : noWingId) {
                failed.add(new FailedLine(item.line().getId(), item.vendorItemId(),
                        NO_WING_ID, NO_WING_ID_MESSAGE));
                record(item, reason, false, null, null, NO_WING_ID, NO_WING_ID_MESSAGE);
            }

            List<OrderLine> writeBack = new ArrayList<>();
            for (Map.Entry<GroupKey, List<Item>> entry : groups.entrySet()) {
                GroupKey key = entry.getKey();
                List<Item> items = entry.getValue();
                MarketplaceAccount account = accountById.get(key.accountId());
                try {
                    CancelResponse response = parse(sendGroup(account, key.orderId(), items, reason));
                    for (Item item : items) {
                        String vendorItemId = item.vendorItemId();
                        if (response.failedVendorItemIds().contains(vendorItemId)) {
                            failed.add(new FailedLine(item.line().getId(), vendorItemId,
                                    response.code(), response.message()));
                            record(item, reason, false, null, null, response.code(), response.message());
                            continue;
                        }
                        Receipt receipt = response.receipts().get(vendorItemId);
                        String receiptId = receipt == null ? null : receipt.receiptId();
                        String receiptType = receipt == null ? null : receipt.receiptType();
                        OrderLine updated = applyCancel(item, receiptType);
                        writeBack.add(updated);
                        OrderStatus resultStatus = updated.effectiveStatus();
                        cancelled.add(new CancelledLine(item.line().getId(), item.quantity(),
                                updated.getCancelQty(), updated.getHoldQty(),
                                updated.purchasableQty(),
                                resultStatus == null ? null : resultStatus.name(),
                                receiptId, receiptType));
                        record(item, reason, true, receiptId, receiptType,
                                response.code(), response.message());
                    }
                } catch (Exception e) {
                    // 그룹 격리: 이 박스의 라인 전체를 실패로. 다른 박스·계정은 계속 보낸다.
                    log.warn("주문취소 전송 실패: account={} order={} box={} lines={}",
                            key.accountId(), key.orderId(), key.boxId(), items.size(), e);
                    for (Item item : items) {
                        failed.add(new FailedLine(item.line().getId(), item.vendorItemId(),
                                "ERROR", e.getMessage()));
                        record(item, reason, false, null, null, "ERROR", e.getMessage());
                    }
                }
            }

            writeBack(writeBack);
        }
    }

    // ── 전송 ──────────────────────────────────────────────────────────────

    /** 박스 1개(= 그룹 1개)를 취소 API 로 보낸다. */
    private String sendGroup(MarketplaceAccount account, String orderId, List<Item> items,
                             OrderCancelReason reason) throws Exception {
        var cred = CoupangCredentials.of(account);
        String path = coupangProperties.getOrderCancelPath()
                .replace("{vendorId}", cred.getVendorId())
                .replace("{orderId}", orderId);

        Map<String, Object> body = new LinkedHashMap<>();
        // external*Id 는 저장 시 String → 요청 바디는 long 으로 변환(발주처리·발송처리와 같은 규칙).
        body.put("orderId", Long.parseLong(orderId));
        // ⚠️ 두 배열의 길이·순서 일치가 API 계약이다 — 한 목록에서 쌍으로 만든다.
        body.put("vendorItemIds", items.stream()
                .map(i -> Long.parseLong(i.vendorItemId())).toList());
        body.put("receiptCounts", items.stream().map(Item::quantity).toList());
        body.put("bigCancelCode", BIG_CANCEL_CODE);
        body.put("middleCancelCode", reason.getMiddleCancelCode());
        body.put("vendorId", cred.getVendorId());
        body.put("userId", cred.getVendorUserId());                 // WING 로그인 ID
        String response = coupangApiClient.post(path, objectMapper.writeValueAsString(body), account);
        // 실계정 미검증 스키마라 첫 dev 실행에서 원문을 눈으로 확인해야 한다(PII 없음: 주문·옵션 id 와 결과뿐).
        log.debug("주문취소 응답 원문: account={} order={} body={}", account.getId(), orderId, response);
        return response;
    }

    /**
     * 응답 파싱 (D9) — {@code data} 가 없으면 예외로 올려 그룹 전체를 실패로 만든다.
     * 스키마가 다르면 조용한 성공이 아니라 시끄러운 실패여야 한다.
     */
    private CancelResponse parse(String json) {
        JsonNode root = readTree(json);
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("쿠팡 주문취소 응답 파싱 실패");
        }
        Set<String> failedIds = new LinkedHashSet<>();
        for (JsonNode id : data.path("failedVendorItemIds")) {
            failedIds.add(id.asText());
        }
        Map<String, Receipt> receipts = new LinkedHashMap<>();
        JsonNode receiptMap = data.path("receiptMap");
        for (Iterator<JsonNode> it = receiptMap.elements(); it.hasNext(); ) {
            JsonNode receipt = it.next();
            String receiptId = receipt.path("receiptId").asText(null);
            String receiptType = receipt.path("receiptType").asText(null);
            for (JsonNode vendorItemId : receipt.path("vendorItemIds")) {
                receipts.put(vendorItemId.asText(), new Receipt(receiptId, receiptType));
            }
        }
        String code = root.path("code").asText("");
        String message = root.path("message").asText("");
        return new CancelResponse(
                failedIds, receipts,
                code.isBlank() ? "FAILED" : code,
                message.isBlank() ? "쿠팡이 취소를 거절했습니다" : message);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 주문취소 응답 파싱 실패", e);
        }
    }

    // ── write-back · 이력 ─────────────────────────────────────────────────

    /**
     * 성공 라인의 수량 반영 (D7) — {@code receiptType} 으로 컬럼을 가른다.
     *
     * <p>{@code CANCEL}(즉시취소)만 {@code cancelQty}, 그 외({@code STOP_SHIPMENT} · 미확인)는
     * {@code holdQty} 다. 출고중지는 <b>확정취소가 아니라서</b> 다음 동기화가 {@code holdCountForCancel}
     * 로 덮어쓴다 — 컬럼을 잘못 고르면 동기화 직후 화면 숫자가 튄다.
     * {@code status} 는 건드리지 않는다 — 쿠팡도 바꾸지 않고, 판정은 {@code effectiveStatus()} 몫이다.
     */
    private OrderLine applyCancel(Item item, String receiptType) {
        OrderLine line = item.line();
        boolean confirmed = RECEIPT_TYPE_CANCEL.equals(receiptType);
        return line.toBuilder()
                .cancelQty(line.getCancelQty() + (confirmed ? item.quantity() : 0))
                .holdQty(line.getHoldQty() + (confirmed ? 0 : item.quantity()))
                .build();
    }

    /** ⚠️ 여기서 던지면 안 된다 — 쿠팡 취소는 이미 일어났다. 실패로 보고하면 사용자가 재전송한다(D7). */
    private void writeBack(List<OrderLine> updated) {
        if (updated.isEmpty()) {
            return;
        }
        try {
            orderLineRepository.saveAll(updated);
        } catch (Exception e) {
            log.warn("주문취소 write-back 실패 (쿠팡 취소는 성공): {}", e.getMessage());
        }
    }

    /**
     * One row per Coupang attempt (D8) — success, failure and {@code NO_WING_ID} are kept.
     *
     * <p>The rule is not "was it sent" but <b>"is it reported to the user as a failure"</b> — the
     * {@code skipped}/{@code unsupported} lines the server filtered out were not the user's choice, so they are not
     * kept. A row without {@code reason} is not saved (FEATURE_2610_17 / D71 guard).
     */
    private void record(Item item, OrderCancelReason reason, boolean succeeded,
                        String receiptId, String receiptType, String code, String message) {
        if (reason == null) {
            // FEATURE_2610_17 / D71: a Coupang row needs its reason — checkReasons stops this before sending, so this
            // is only a guard; the row is not saved.
            log.error("주문취소 감사기록 생략(사유 없음): line={} succeeded={}", item.line().getId(), succeeded);
            return;
        }
        try {
            orderCancelActionRepository.save(OrderCancelAction.builder()
                    .orderLine(item.line())
                    .quantity(item.quantity())
                    .reason(reason)
                    .platformReasonCode(reason.getMiddleCancelCode())
                    // 🔴 플랫폼 원문(ACCEPT/INSTRUCT)을 넣는다 — 이 컬럼의 용도가 "즉시취소였나 출고중지였나"
                    // 의 근거라 원본은 전송 시점의 플랫폼 상태다(PLAN D1). 중립 이름을 넣으면 한 컬럼에
                    // 두 어휘가 섞인다. 과거 행 백필도 하지 않는다 — 그때 그 값이 사실이다.
                    .statusAtSend(item.platformStatus())
                    .succeeded(succeeded)
                    .receiptId(receiptId)
                    .receiptType(receiptType)
                    .resultCode(code)
                    .resultMessage(OrderCancelAdapter.truncate(message))
                    .createdBy(OrderCancelAdapter.currentUsername())
                    .build());
        } catch (Exception e) {
            // 기록 실패가 전송 결과를 뒤집으면 안 된다 — 이미 보낸 것은 되돌릴 수 없다.
            log.error("주문취소 감사기록 실패: line={} succeeded={}", item.line().getId(), succeeded, e);
        }
    }

    /** 전송 단위 = 계정 × 주문번호 × 박스(D1) — 쿠팡이 박스별 호출을 요구한다. */
    private record GroupKey(Long accountId, String orderId, String boxId) {
    }

    /**
     * 취소 대상 라인 1건 + 쿠팡 거울 + 요청 수량.
     *
     * <p>거울을 함께 들고 다니는 이유: 전송 식별자({@code vendorItemId})와 이력의 근거
     * ({@code platform_status})가 둘 다 플랫폼 쪽 값이라, 라인마다 다시 조회하면 N+1 이 된다.
     */
    private record Item(OrderLine line, CoupangOrderLine mirror, int quantity) {

        String vendorItemId() {
            return mirror.getVendorItemId();
        }

        String platformStatus() {
            return mirror.getPlatformStatus();
        }
    }

    /** 접수 1건의 식별자 — {@code receiptType} 이 write-back 컬럼을 가른다. */
    private record Receipt(String receiptId, String receiptType) {
    }

    /** 파싱한 응답. {@code code}/{@code message} 는 쿠팡 원문(실패 라인 보고용). */
    private record CancelResponse(Set<String> failedVendorItemIds, Map<String, Receipt> receipts,
                                  String code, String message) {
    }
}
