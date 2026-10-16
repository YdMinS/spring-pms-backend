package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderCancelAction;
import com.pms.domain.OrderCancelReason;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderCancelActionRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.OrderCancelResult.CancelledLine;
import com.pms.service.OrderCancelResult.FailedLine;
import com.pms.service.OrderCancelResult.SkippedLine;
import com.pms.service.claim.ActionChoice;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.elevenst.ElevenstRejectedException;
import com.pms.service.elevenst.ElevenstSellerActions;
import com.pms.service.elevenst.ElevenstSellerChoices;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link OrderCancelService} implementation — the seller cancel before shipping (FEATURE_2609_25). 11st lines take
 * the sale-refusal path (1638, FEATURE_2610_17 / D61) — see {@link #cancelElevenst}; the rest of this note describes
 * the Coupang leg.
 *
 * Flow: duplicate line-id check → {@code findWithAccountByIdIn} expand → reason check of each platform in the request
 * (FEATURE_2610_17 / D71) → line sort (non-Coupang · no box = unsupported, status outside · fully cancelled =
 * skipped, quantity over = 400, no WING ID = failed) → group by account · order · box → one POST per group →
 * {@code failedVendorItemIds}/{@code receiptMap} judgement → write-back per receiptType → history rows.
 *
 * ⚠️ Send failures are isolated by a try/catch per group — one failing box does not stop the other boxes.
 * ⚠️ Never put {@code @Transactional} on this service — it is a path that runs external HTTP.
 *    {@code OrderLine} is read only through {@code @EntityGraph} finders (open-in-view=false).
 * ⚠️ A failed write-back or history save does not flip the result — the Coupang cancel already happened, and
 *    reporting it as failed would make the user send it again (D7·D8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelServiceImpl implements OrderCancelService {

    /**
     * Statuses a seller cancel is sent from — Coupang answers any other with a 400 (D2), and 11st sale refusal takes
     * paid · preparing too (FEATURE_2610_17 / D61). An irreversible write, so nothing else is sent.
     */
    private static final Set<OrderStatus> CANCELLABLE_STATUSES = Set.of(
            OrderStatus.PAID, OrderStatus.PREPARING);

    /** 쿠팡 {@code bigCancelCode} 는 이 값 하나뿐이다(판매자 귀책 취소). */
    private static final String BIG_CANCEL_CODE = "CANERR";

    /** 즉시취소 접수 — 취소확정 수량으로 반영한다. 그 외(출고중지·미확인)는 보류 수량으로 간다(D7). */
    private static final String RECEIPT_TYPE_CANCEL = "CANCEL";

    /** Receipt type of an 11st sale refusal sent to 11st (FEATURE_2610_17 / D61). */
    static final String ELEVENST_SALE_REFUSAL = "ELEVENST_SALE_REFUSAL";

    /** FEATURE_2610_17 / D71: the reason check of both platforms, before anything is sent. */
    static final String REASON_REQUIRED_MESSAGE = "취소 사유를 선택하세요";
    static final String ELEVENST_REASON_TEXT_MESSAGE = "사유 글은 1~200자로 적어 주세요.";
    private static final int ELEVENST_REASON_TEXT_MAX = 200;

    private static final String NO_WING_ID = "NO_WING_ID";
    private static final String NO_WING_ID_MESSAGE =
            "판매채널 설정에 WING 로그인 ID를 등록해야 취소할 수 있습니다";

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderLineRepository orderLineRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final OrderCancelActionRepository orderCancelActionRepository;
    private final ObjectMapper objectMapper;
    /** FEATURE_2610_17 / D61: 11st sale refusal (1638). */
    private final ElevenstSellerActions elevenstSellerActions;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;

    @Override
    public OrderCancelResult cancel(OrderCancelRequest request) {
        List<Long> ids = request.lines().stream().map(OrderCancelRequest.Line::orderItemId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            // 조용히 합치지 않는다 — 합치면 사용자가 의도하지 않은 수량이 되돌릴 수 없는 API 로 나간다.
            throw new IllegalArgumentException("같은 주문 라인이 중복되었습니다");
        }
        Map<Long, Integer> quantityById = new LinkedHashMap<>();
        request.lines().forEach(l -> quantityById.put(l.orderItemId(), l.quantity()));

        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(ids);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }
        OrderCancelReason reason = request.reason();
        ElevenstReason elevenstReason = checkReasons(lines, quantityById, request);
        // vendorItemId(전송 식별자)와 platform_status(이력의 근거)는 쿠팡 거울에 있다 — 한 번에 읽는다(04 §3-3).
        Map<Long, CoupangOrderLine> mirrors = coupangOrderLineRepository
                .findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));

        List<CancelledLine> cancelled = new ArrayList<>();
        List<FailedLine> failed = new ArrayList<>();
        List<SkippedLine> skipped = new ArrayList<>();
        List<SkippedLine> unsupported = new ArrayList<>();
        List<Item> noWingId = new ArrayList<>();
        Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        Map<GroupKey, List<Item>> groups = new LinkedHashMap<>();
        List<OrderLine> elevenstLines = new ArrayList<>();

        for (OrderLine line : lines) {
            Integer quantity = quantityById.get(line.getId());
            if (quantity == null) {
                continue;                                   // 요청에 없는 라인은 무시(방어)
            }
            MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
            if (Platform.ELEVENST.equals(account.getPlatform())) {
                // FEATURE_2610_17 / D61: the whole line goes to 11st (1638) — the quantity is not used.
                if (line.getStatus() == null || !CANCELLABLE_STATUSES.contains(line.getStatus())) {
                    skipped.add(skippedLine(line, "취소할 수 없는 상태입니다"));
                } else if (line.isFullyCancelled()) {
                    skipped.add(skippedLine(line, "이미 전량 취소된 주문입니다"));
                } else {
                    elevenstLines.add(line);
                }
                continue;
            }
            String boxId = (line.getOrderShipment() == null)
                    ? null : line.getOrderShipment().getExternalShipmentId();
            CoupangOrderLine mirror = mirrors.get(line.getId());
            // 플랫폼 판정은 계정 기준 — 발주처리·발송처리와 같은 기준이어야 레그가 갈라지지 않는다.
            // 거울 행이 없으면 전송 식별자(vendorItemId)를 만들 수 없어 같은 부류로 묶는다.
            if (!Platform.COUPANG.equals(account.getPlatform()) || boxId == null || boxId.isBlank()
                    || mirror == null) {
                unsupported.add(skippedLine(line, "쿠팡 · 11번가 주문이 아니거나 배송번호가 없습니다"));
                continue;
            }
            if (line.getStatus() == null || !CANCELLABLE_STATUSES.contains(line.getStatus())) {
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
                noWingId.add(new Item(line, mirror, quantity));
                continue;
            }
            accountById.putIfAbsent(account.getId(), account);
            groups.computeIfAbsent(
                    new GroupKey(account.getId(), line.getOrder().getExternalOrderId(), boxId),
                    k -> new ArrayList<>()).add(new Item(line, mirror, quantity));
        }

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
                CancelResponse response = parse(send(account, key.orderId(), items, reason));
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
        if (!elevenstLines.isEmpty()) {
            cancelElevenst(elevenstLines, elevenstReason, cancelled, failed);
        }

        int succeededQty = cancelled.stream().mapToInt(CancelledLine::cancelledQty).sum();
        log.info("주문취소 결과: lines={} succeeded={} qty={} failed={} skipped={} unsupported={}",
                lines.size(), cancelled.size(), succeededQty,
                failed.size(), skipped.size(), unsupported.size());

        return new OrderCancelResult(lines.size(), cancelled.size(), succeededQty,
                cancelled, failed, skipped, unsupported);
    }

    /**
     * FEATURE_2610_17 / D71: the reason of each platform present in the request is checked here — after the lines are
     * read (the platform is known) and before any grouping or sending, so one bad reason sends nothing. A Coupang line
     * needs {@code reason} (the DB no longer holds it NOT NULL — D70); an 11st line needs a listed {@code reasonCode}
     * and a 1~200 character {@code reasonText}.
     *
     * @return the trimmed 11st reason, or {@code null} when the request has no 11st line
     */
    private ElevenstReason checkReasons(List<OrderLine> lines, Map<Long, Integer> quantityById,
                                        OrderCancelRequest request) {
        boolean coupang = false;
        boolean elevenst = false;
        for (OrderLine line : lines) {
            if (!quantityById.containsKey(line.getId())) {
                continue;
            }
            Platform platform = line.getOrder().getMarketplaceAccount().getPlatform();
            coupang |= Platform.COUPANG.equals(platform);
            elevenst |= Platform.ELEVENST.equals(platform);
        }
        if (coupang && request.reason() == null) {
            throw new IllegalArgumentException(REASON_REQUIRED_MESSAGE);
        }
        if (!elevenst) {
            return null;
        }
        String code = request.reasonCode() == null ? null : request.reasonCode().trim();
        ElevenstSellerChoices.requireLabel(ElevenstSellerChoices.SELLER_CANCEL_REASONS, code, REASON_REQUIRED_MESSAGE);
        String text = request.reasonText() == null ? "" : request.reasonText().trim();
        if (text.isEmpty() || text.length() > ELEVENST_REASON_TEXT_MAX) {
            throw new IllegalArgumentException(ELEVENST_REASON_TEXT_MESSAGE);
        }
        return new ElevenstReason(code, text);
    }

    /**
     * 11st sale refusal (1638, FEATURE_2610_17 / D61): one call per line, the whole line. The line is not changed
     * here — the next sync reads it from the 11st cancel-completed list (1642) as {@code cancel_qty} (D14). A success
     * is reported as a cancelled line with {@code cancelledQty} 0 and receipt type
     * {@value #ELEVENST_SALE_REFUSAL}; a refusal keeps 11st's own code and wording. Every attempt reported to the user
     * leaves one history row (D70).
     */
    private void cancelElevenst(List<OrderLine> lines, ElevenstReason reason, List<CancelledLine> cancelled,
                                List<FailedLine> failed) {
        Map<Long, ElevenstOrderLine> mirrors = elevenstOrderLineRepository
                .findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        for (OrderLine line : lines) {
            ElevenstOrderLine mirror = mirrors.get(line.getId());
            if (mirror == null) {
                failed.add(new FailedLine(line.getId(), null, "ERROR", "11번가 주문 정보가 없습니다"));
                recordElevenst(line, null, reason, false, "ERROR", "11번가 주문 정보가 없습니다");
                continue;
            }
            try {
                ElevenstSellerActions.Outcome outcome = elevenstSellerActions.rejectOrder(
                        line.getOrder().getMarketplaceAccount(), mirror, reason.code(), reason.text());
                cancelled.add(new CancelledLine(line.getId(), 0, line.getCancelQty(), line.getHoldQty(),
                        line.purchasableQty(), line.getStatus().name(), null, ELEVENST_SALE_REFUSAL));
                recordElevenst(line, mirror.getPlatformStatus(), reason, true, outcome.code(), outcome.text());
                log.info("11번가 판매불가 처리: line={} ordNo={} reason={}", line.getId(), mirror.getOrdNo(),
                        reason.code());
            } catch (ElevenstRejectedException e) {
                String code = e.getResultCode() == null ? "ERROR" : e.getResultCode();
                String message = e.getResultText() == null ? e.getMessage() : e.getResultText();
                failed.add(new FailedLine(line.getId(), mirror.getPrdStckNo(), code, message));
                recordElevenst(line, mirror.getPlatformStatus(), reason, false, code, message);
            } catch (Exception e) {
                log.warn("11번가 판매불가 처리 실패: line={}", line.getId(), e);
                failed.add(new FailedLine(line.getId(), mirror.getPrdStckNo(), "ERROR", e.getMessage()));
                recordElevenst(line, mirror.getPlatformStatus(), reason, false, "ERROR", e.getMessage());
            }
        }
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
                    .resultMessage(truncate(message))
                    .createdBy(currentUsername())
                    .build());
        } catch (Exception e) {
            // 기록 실패가 전송 결과를 뒤집으면 안 된다 — 이미 보낸 것은 되돌릴 수 없다.
            log.error("주문취소 감사기록 실패: line={} succeeded={}", item.line().getId(), succeeded, e);
        }
    }

    /**
     * One history row of an 11st sale refusal (FEATURE_2610_17 / D70): {@code reason} stays {@code null}, the 11st
     * code goes to {@code platform_reason_code} and the seller's text to {@code reason_text}; the quantity is the
     * line's remaining quantity right before sending. A failed save is only logged, like {@link #record}.
     */
    private void recordElevenst(OrderLine line, String statusAtSend, ElevenstReason reason, boolean succeeded,
                                String code, String message) {
        try {
            orderCancelActionRepository.save(OrderCancelAction.builder()
                    .orderLine(line)
                    .quantity(line.purchasableQty())
                    .platformReasonCode(reason.code())
                    .reasonText(reason.text())
                    .statusAtSend(statusAtSend)
                    .succeeded(succeeded)
                    .receiptType(ELEVENST_SALE_REFUSAL)
                    .resultCode(code)
                    .resultMessage(truncate(message))
                    .createdBy(currentUsername())
                    .build());
        } catch (Exception e) {
            log.error("11번가 판매불가 감사기록 실패: line={} succeeded={}", line.getId(), succeeded, e);
        }
    }

    private SkippedLine skippedLine(OrderLine line, String reason) {
        return new SkippedLine(line.getId(), line.getOrder().getExternalOrderId(),
                line.getStatus() == null ? null : line.getStatus().name(), reason);
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

    /** The checked 11st sale-refusal reason of a request: listed code + trimmed 1~200 character text (D71). */
    private record ElevenstReason(String code, String text) {
    }

    /** 접수 1건의 식별자 — {@code receiptType} 이 write-back 컬럼을 가른다. */
    private record Receipt(String receiptId, String receiptType) {
    }

    /** 파싱한 응답. {@code code}/{@code message} 는 쿠팡 원문(실패 라인 보고용). */
    private record CancelResponse(Set<String> failedVendorItemIds, Map<String, Receipt> receipts,
                                  String code, String message) {
    }
}
