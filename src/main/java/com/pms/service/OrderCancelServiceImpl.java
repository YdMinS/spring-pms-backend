package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderCancelAction;
import com.pms.domain.OrderCancelReason;
import com.pms.domain.OrderItem;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.repository.OrderCancelActionRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.service.OrderCancelResult.CancelledLine;
import com.pms.service.OrderCancelResult.FailedLine;
import com.pms.service.OrderCancelResult.SkippedLine;
import com.pms.service.claim.ActionChoice;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.coupang.CoupangOrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link OrderCancelService} 구현 — COUPANG 전용 발송 전 취소 레그 (FEATURE_2609_25).
 *
 * 흐름: 라인 id 중복 검사 → {@code findWithAccountByIdIn} 전개 → 라인 분류(비-쿠팡·박스 없음=unsupported,
 * 상태 밖·전량취소=skipped, 수량 초과=400, WING ID 없음=failed) → 계정·주문·박스 그룹 → 그룹마다 1 POST →
 * {@code failedVendorItemIds}/{@code receiptMap} 판정 → receiptType 별 write-back → 이력 적재.
 *
 * ⚠️ 그룹 단위 try/catch 로 전송 실패를 격리한다 — 한 박스가 실패해도 다른 박스는 계속 보낸다.
 * ⚠️ 이 서비스에 {@code @Transactional} 을 붙이면 안 된다 — 외부 HTTP 를 도는 경로다.
 *    {@code OrderItem} 조회는 {@code @EntityGraph} finder 로만 한다(open-in-view=false).
 * ⚠️ write-back·이력 저장 실패는 결과를 뒤집지 않는다 — 쿠팡 취소는 이미 일어났고, 실패로 보고하면
 *    사용자가 재전송한다(D7·D8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelServiceImpl implements OrderCancelService {

    /** 취소를 받아 주는 상태 화이트리스트 — 그 외는 쿠팡이 400 을 준다(D2). 되돌릴 수 없는 쓰기라 안 보낸다. */
    private static final Set<String> CANCELLABLE_STATUSES = Set.of(
            CoupangOrderStatus.ACCEPT.name(), CoupangOrderStatus.INSTRUCT.name());

    /** 쿠팡 {@code bigCancelCode} 는 이 값 하나뿐이다(판매자 귀책 취소). */
    private static final String BIG_CANCEL_CODE = "CANERR";

    /** 즉시취소 접수 — 취소확정 수량으로 반영한다. 그 외(출고중지·미확인)는 보류 수량으로 간다(D7). */
    private static final String RECEIPT_TYPE_CANCEL = "CANCEL";

    private static final String NO_WING_ID = "NO_WING_ID";
    private static final String NO_WING_ID_MESSAGE =
            "판매채널 설정에 WING 로그인 ID를 등록해야 취소할 수 있습니다";

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderItemRepository orderItemRepository;
    private final OrderCancelActionRepository orderCancelActionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public OrderCancelResult cancel(OrderCancelRequest request) {
        List<Long> ids = request.lines().stream().map(OrderCancelRequest.Line::orderItemId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            // 조용히 합치지 않는다 — 합치면 사용자가 의도하지 않은 수량이 되돌릴 수 없는 API 로 나간다.
            throw new IllegalArgumentException("같은 주문 라인이 중복되었습니다");
        }
        Map<Long, Integer> quantityById = new LinkedHashMap<>();
        request.lines().forEach(l -> quantityById.put(l.orderItemId(), l.quantity()));

        List<OrderItem> lines = orderItemRepository.findWithAccountByIdIn(ids);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }

        OrderCancelReason reason = request.reason();
        List<CancelledLine> cancelled = new ArrayList<>();
        List<FailedLine> failed = new ArrayList<>();
        List<SkippedLine> skipped = new ArrayList<>();
        List<SkippedLine> unsupported = new ArrayList<>();
        List<Item> noWingId = new ArrayList<>();
        Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        Map<GroupKey, List<Item>> groups = new LinkedHashMap<>();

        for (OrderItem line : lines) {
            Integer quantity = quantityById.get(line.getId());
            if (quantity == null) {
                continue;                                   // 요청에 없는 라인은 무시(방어)
            }
            MarketplaceAccount account = line.getMarketplaceAccount();
            String boxId = line.getExternalBoxId();
            // 플랫폼 판정은 계정 기준 — 발주처리·발송처리와 같은 기준이어야 레그가 갈라지지 않는다.
            if (!Platform.COUPANG.equals(account.getPlatform()) || boxId == null || boxId.isBlank()) {
                unsupported.add(skippedLine(line, "쿠팡 주문이 아니거나 배송번호가 없습니다"));
                continue;
            }
            if (!CANCELLABLE_STATUSES.contains(line.getStatus())) {
                skipped.add(skippedLine(line, "취소할 수 없는 상태입니다"));
                continue;
            }
            if (line.isFullyCancelled()) {
                skipped.add(skippedLine(line, "이미 전량 취소된 주문입니다"));
                continue;
            }
            if (quantity > line.purchasableQty()) {
                // 되돌릴 수 없는 API 다 — 왕복 전에 전체 요청을 막는다(D3, 부분 전송 금지).
                throw new IllegalArgumentException(
                        "취소 수량이 취소 가능 수량(" + line.purchasableQty() + "개)을 초과했습니다");
            }
            String wingUserId = CoupangCredentials.of(account).getVendorUserId();
            if (wingUserId == null || wingUserId.isBlank()) {
                // WING ID 는 API 필수값이라 없으면 400 이 확정 — 왕복하지 않는다(D10).
                noWingId.add(new Item(line, quantity));
                continue;
            }
            accountById.putIfAbsent(account.getId(), account);
            groups.computeIfAbsent(
                    new GroupKey(account.getId(), line.getExternalOrderId(), boxId),
                    k -> new ArrayList<>()).add(new Item(line, quantity));
        }

        for (Item item : noWingId) {
            failed.add(new FailedLine(item.line().getId(), item.line().getExternalItemId(),
                    NO_WING_ID, NO_WING_ID_MESSAGE));
            record(item, reason, false, null, null, NO_WING_ID, NO_WING_ID_MESSAGE);
        }

        List<OrderItem> writeBack = new ArrayList<>();
        for (Map.Entry<GroupKey, List<Item>> entry : groups.entrySet()) {
            GroupKey key = entry.getKey();
            List<Item> items = entry.getValue();
            MarketplaceAccount account = accountById.get(key.accountId());
            try {
                CancelResponse response = parse(send(account, key.orderId(), items, reason));
                for (Item item : items) {
                    String vendorItemId = item.line().getExternalItemId();
                    if (response.failedVendorItemIds().contains(vendorItemId)) {
                        failed.add(new FailedLine(item.line().getId(), vendorItemId,
                                response.code(), response.message()));
                        record(item, reason, false, null, null, response.code(), response.message());
                        continue;
                    }
                    Receipt receipt = response.receipts().get(vendorItemId);
                    String receiptId = receipt == null ? null : receipt.receiptId();
                    String receiptType = receipt == null ? null : receipt.receiptType();
                    OrderItem updated = applyCancel(item, receiptType);
                    writeBack.add(updated);
                    cancelled.add(new CancelledLine(item.line().getId(), item.quantity(),
                            updated.getCancelCount(), updated.getHoldCount(),
                            updated.purchasableQty(), updated.effectiveStatus(),
                            receiptId, receiptType));
                    record(item, reason, true, receiptId, receiptType,
                            response.code(), response.message());
                }
            } catch (Exception e) {
                // 그룹 격리: 이 박스의 라인 전체를 실패로. 다른 박스·계정은 계속 보낸다.
                log.warn("주문취소 전송 실패: account={} order={} box={} lines={}",
                        key.accountId(), key.orderId(), key.boxId(), items.size(), e);
                for (Item item : items) {
                    failed.add(new FailedLine(item.line().getId(), item.line().getExternalItemId(),
                            "ERROR", e.getMessage()));
                    record(item, reason, false, null, null, "ERROR", e.getMessage());
                }
            }
        }

        writeBack(writeBack);

        int succeededQty = cancelled.stream().mapToInt(CancelledLine::cancelledQty).sum();
        log.info("주문취소 결과: lines={} succeeded={} qty={} failed={} skipped={} unsupported={}",
                lines.size(), cancelled.size(), succeededQty,
                failed.size(), skipped.size(), unsupported.size());

        return new OrderCancelResult(lines.size(), cancelled.size(), succeededQty,
                cancelled, failed, skipped, unsupported);
    }

    @Override
    public List<ActionChoice> availableReasons() {
        return Arrays.stream(OrderCancelReason.values())
                .map(r -> new ActionChoice(r.name(), r.getLabel()))
                .toList();
    }

    // ── 전송 ──────────────────────────────────────────────────────────────

    /** 박스 1개(= 그룹 1개)를 취소 API 로 보낸다. */
    private String send(MarketplaceAccount account, String orderId, List<Item> items,
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
                .map(i -> Long.parseLong(i.line().getExternalItemId())).toList());
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
     * <p>{@code CANCEL}(즉시취소)만 {@code cancelCount}, 그 외({@code STOP_SHIPMENT} · 미확인)는
     * {@code holdCount} 다. 출고중지는 <b>확정취소가 아니라서</b> 다음 동기화가 {@code holdCountForCancel}
     * 로 덮어쓴다 — 컬럼을 잘못 고르면 동기화 직후 화면 숫자가 튄다.
     * {@code status} 는 건드리지 않는다 — 쿠팡도 바꾸지 않고, 판정은 {@code effectiveStatus()} 몫이다.
     */
    private OrderItem applyCancel(Item item, String receiptType) {
        OrderItem line = item.line();
        boolean confirmed = RECEIPT_TYPE_CANCEL.equals(receiptType);
        return line.toBuilder()
                .cancelCount(line.getCancelCount() + (confirmed ? item.quantity() : 0))
                .holdCount(line.getHoldCount() + (confirmed ? 0 : item.quantity()))
                .build();
    }

    /** ⚠️ 여기서 던지면 안 된다 — 쿠팡 취소는 이미 일어났다. 실패로 보고하면 사용자가 재전송한다(D7). */
    private void writeBack(List<OrderItem> updated) {
        if (updated.isEmpty()) {
            return;
        }
        try {
            orderItemRepository.saveAll(updated);
        } catch (Exception e) {
            log.warn("주문취소 write-back 실패 (쿠팡 취소는 성공): {}", e.getMessage());
        }
    }

    /**
     * 시도 1건 = 1행 (D8). 성공·실패·{@code NO_WING_ID} 를 남긴다.
     *
     * <p>기준은 "전송했는가"가 아니라 <b>"사용자에게 실패로 보고되는가"</b> 다 —
     * 서버가 걸러낸 {@code skipped}/{@code unsupported} 는 사용자가 고른 것이 아니라 남기지 않는다.
     */
    private void record(Item item, OrderCancelReason reason, boolean succeeded,
                        String receiptId, String receiptType, String code, String message) {
        try {
            orderCancelActionRepository.save(OrderCancelAction.builder()
                    .orderItem(item.line())
                    .quantity(item.quantity())
                    .reason(reason)
                    .platformReasonCode(reason.getMiddleCancelCode())
                    .statusAtSend(item.line().getStatus())
                    .succeeded(succeeded)
                    .receiptId(receiptId)
                    .receiptType(receiptType)
                    .resultCode(code)
                    .resultMessage(truncate(message))
                    .createdBy(currentUsername())
                    .build());
        } catch (Exception e) {
            // 기록 실패가 전송 결과를 뒤집으면 안 된다 — 이미 보낸 것은 되돌릴 수 없다.
            log.error("주문취소 감사기록 실패: line={} succeeded={}", item.line().getId(), succeeded, e);
        }
    }

    private SkippedLine skippedLine(OrderItem line, String reason) {
        return new SkippedLine(line.getId(), line.getExternalOrderId(), line.getStatus(), reason);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }

    /** 전송 단위 = 계정 × 주문번호 × 박스(D1) — 쿠팡이 박스별 호출을 요구한다. */
    private record GroupKey(Long accountId, String orderId, String boxId) {
    }

    /** 취소 대상 라인 1건 + 요청 수량. */
    private record Item(OrderItem line, int quantity) {
    }

    /** 접수 1건의 식별자 — {@code receiptType} 이 write-back 컬럼을 가른다. */
    private record Receipt(String receiptId, String receiptType) {
    }

    /** 파싱한 응답. {@code code}/{@code message} 는 쿠팡 원문(실패 라인 보고용). */
    private record CancelResponse(Set<String> failedVendorItemIds, Map<String, Receipt> receipts,
                                  String code, String message) {
    }
}
