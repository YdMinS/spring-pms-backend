package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.coupang.OrderUpserter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Coupang invoice registration (FEATURE_2610_23 / D21), moved from {@link ShipmentConfirmServiceImpl} unchanged (D3).
 *
 * Result file: line (box) status filter (DEPARTURE and later are not sent and are reported as skipped, PLAN 2609_07
 * D1·D2) → (orders missing from the DB go through the Coupang single-order fallback — the same filter on its box
 * status, D6; Coupang only, FEATURE_2610_23 / D25) → group by account → one POST per account (a combined box shares
 * shipmentBoxId · invoiceNumber, only vendorItemId differs) → sum the responseList → write SHIPPED back to the
 * succeeded boxes (D4).
 *
 * ⚠️ One account failing (carrier code not set IllegalStateException · send · parse error included) is isolated with
 *    a try/catch per account — the other batches continue.
 * ⚠️ The fallback (shipping-label PLAN D16) is the safety net that lets a sheet ship even when the sync failed and
 *    the order lines are empty — the normal path (DB match) never calls Coupang.
 * ⚠️ Boxes fetched by the fallback are loaded into {@code order_line} (PLAN 2609_13 D1·D9) — best-effort, so a failed
 *    save still sends the invoice (D6).
 *
 * <p>Single manual (PLAN 2609_11) shares only the send helper ({@code postInvoices}) and judges on its own: it expands
 * <b>only the anchor line's box</b> (other boxes of the order are untouched, D1), the server picks the mode — invoice
 * update (updateInvoices) when the anchor is DEPARTURE or later, else a new upload (D3) — and writes SHIPPED back
 * <b>only on a new-upload success</b> (update mode never changes the status, D4).
 *
 * <p>Coupang fills the internal-stage slot {@link #sendReservedInvoices} (D23 · D24).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangShipmentInvoiceAdapter implements ShipmentInvoiceAdapter {

    /** 폴백 대상 주문 수 상한. 초과분은 조회하지 않고 미매칭으로 남긴다(쿠팡 호출 폭주 방지). */
    private static final int MAX_FALLBACK_ORDERS = 50;
    /** 연속 이 횟수만큼 후보 계정 전부에서 실패하면 폴백을 전면 중단한다(쿠팡 장애 시 스레드 점유 방지). */
    private static final int FALLBACK_CIRCUIT_THRESHOLD = 3;

    /**
     * 전송하지 않을 상태 — 이 상태는 되돌아오지 않으므로(상태 단조성) 조회 없이 제외해도 누락이 불가능하다.
     * ⚠️ 화이트리스트가 아니라 블랙리스트다: 모르는 상태값(매핑 실패·null)은 <b>전송</b>한다
     *    (조용한 스킵 = 발송 누락, 전송 = 쿠팡이 판단). PLAN 2609_07 D1.
     * ⚠️ {@code CANCELLED} 는 넣지 않는다 — 전량취소 라인은 지금도 라인 수 0 으로 걸러진다(판정 두 겹 금지).
     */
    private static final Set<OrderStatus> SKIP_STATUSES = Set.of(
            OrderStatus.SHIPPED, OrderStatus.DELIVERING, OrderStatus.DELIVERED);

    /**
     * 송장수정(UPDATE) 모드로 보낼 상태 — "배송지시 이상"이라는 뜻이 {@link #SKIP_STATUSES} 와 같으므로 같은 집합을 가리킨다.
     * ⚠️ 이름을 갈라 두는 이유: 일괄 경로의 "전송 스킵"과 단건 경로의 "모드 판정"은 서로 다른 관심사다.
     *    스킵 목록에 상태를 더할 일이 생기면 단건 모드가 조용히 UPDATE 로 뒤집히지 않게 여기서 분리할 것(PLAN 2609_11 D3).
     */
    private static final Set<OrderStatus> UPDATE_MODE_STATUSES = SKIP_STATUSES;

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderLineRepository orderLineRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CarrierCodeService carrierCodeService;
    private final ObjectMapper objectMapper;
    private final OrderUpserter orderUpserter;
    private final ShipmentParcelRecorder shipmentParcelRecorder;
    private final InvoiceWriteBack invoiceWriteBack;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    @Override
    public FileOutcome shipFromFile(ResultFile file, Map<String, List<OrderLine>> orders) {
        Map<String, List<String>> invoicesByOrderId = file.invoicesByOrderId();
        Map<Platform, String> carrierCodeCache = file.carrierCodeCache();
        List<String> unmatched = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        Map<Long, List<InvoiceLine>> linesByAccount = new LinkedHashMap<>();
        // 송장업로드 성공 시 status 를 갱신할 DB 라인(박스 단위).
        // 폴백으로 확정된 주문도 조회 시점에 적재되므로(PLAN 2609_13 D9) 여기 편입된다.
        Map<String, List<OrderLine>> dbLinesByBoxId = new LinkedHashMap<>();
        int matchedOrders = 0;

        for (Map.Entry<String, List<OrderLine>> order : orders.entrySet()) {
            String orderId = order.getKey();
            List<OrderLine> lines = order.getValue();
            MarketplaceAccount account = lines.get(0).getOrder().getMarketplaceAccount();
            // 🔴 실물 박스 저장은 여기 한 곳이다(PLAN 2609_40 D8·D30) — 전송 전, 상태 필터 전.
            //    전량 발송 완료로 아래에서 skip 되는 주문도 송장은 이미 발급됐으므로 박스를 남긴다.
            invoiceWriteBack.recordParcels(orderId, lines, invoicesByOrderId.get(orderId), carrierCodeCache);
            // 한 주문에 박스가 여러 개면 박스마다 상태가 다를 수 있다(order_line.status = box.status 의 거울).
            List<OrderLine> sendable = lines.stream()
                    .filter(this::sendable)
                    .toList();
            if (sendable.isEmpty()) {
                // 전량 발송 완료 — 쿠팡 호출 0회. unmatched 가 아니다(계정도 라인도 확정돼 있다).
                skipped.add(new SkippedOrder(orderId, InvoiceWriteBack.statusName(lines.get(0)),
                        ShipmentConfirmResult.ALREADY_SHIPPED_REASON));
                continue;
            }
            matchedOrders++;
            accountById.putIfAbsent(account.getId(), account);
            linesByAccount.computeIfAbsent(account.getId(), k -> new ArrayList<>())
                    .addAll(toInvoiceLines(sendable));
            invoiceWriteBack.registerWriteBack(sendable, dbLinesByBoxId);
        }

        matchedOrders += fallback(file.missingOrderIds(), unmatched, skipped, accountById, linesByAccount,
                dbLinesByBoxId, invoicesByOrderId, carrierCodeCache);

        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        for (Map.Entry<Long, List<InvoiceLine>> entry : linesByAccount.entrySet()) {
            MarketplaceAccount account = accountById.get(entry.getKey());
            List<InvoiceLine> lines = entry.getValue();
            try {
                AccountResult result = sendBatch(account, lines, invoicesByOrderId);
                succeeded += result.succeeded();
                invoiceWriteBack.markDeparted(result.succeededBoxIds(), dbLinesByBoxId);
                failed.addAll(result.failed());
            } catch (Exception e) {
                // 계정 격리: 이 계정의 박스 전체를 실패로(메시지=예외), 다른 계정 배치는 유지.
                log.warn("발송처리 계정 배치 실패: account={} platform={}",
                        account.getId(), account.getPlatform(), e);
                for (String boxId : distinctBoxIds(lines)) {
                    failed.add(new FailedBox(boxId, "ERROR", e.getMessage()));
                }
            }
        }
        return new FileOutcome(matchedOrders, succeeded, failed, skipped, unmatched);
    }

    @Override
    public ManualShipmentResult shipManual(ManualShipmentRequest request, OrderLine anchor,
                                           MarketplaceAccount account, String invoiceNumber) {
        String boxId = InvoiceWriteBack.boxIdOf(anchor);
        if (boxId == null || boxId.isBlank()) {
            throw new IllegalArgumentException("박스 ID가 없습니다. 주문동기화 후 다시 시도하세요");   // D7
        }
        String externalOrderId = anchor.getOrder().getExternalOrderId();
        // 2) 박스 전개 — 같은 주문 + 같은 계정 + 같은 박스 라인 전부(D1). 취소 라인은 거르지 않는다(D8).
        List<OrderLine> boxLines = orderLineRepository.findByExternalOrderId(externalOrderId)
                .stream()
                .filter(l -> l.getOrder().getMarketplaceAccount().getId().equals(account.getId()))
                .filter(l -> boxId.equals(InvoiceWriteBack.boxIdOf(l)))
                .toList();
        if (boxLines.isEmpty()) {
            boxLines = List.of(anchor);      // 방어적: 앵커는 항상 그 박스의 라인이다
        }
        // FEATURE_2609_75 / D27 — 내부 단계 묶음은 발주처리 전이다. 송장만 보내면 쿠팡이 거절하고, 실물 박스만 남는다.
        if (InternalStageMarkets.hasInternalStage(boxLines)) {
            throw new IllegalArgumentException(ShipmentConfirmServiceImpl.INTERNAL_STAGE_MANUAL_MESSAGE);
        }
        // 3) 모드 판정 = 앵커 상태(박스 상태의 거울). 클라이언트는 관여하지 않는다(D3).
        boolean update = anchor.getStatus() != null && UPDATE_MODE_STATUSES.contains(anchor.getStatus());
        String mode = update ? "UPDATE" : "CREATE";
        // 택배사 코드를 먼저 해석한다 — 미등록이면 400 이므로 설정(경로)을 읽기 전에 끝낸다.
        String deliveryCompanyCode = carrierCodeService
                .validateDeliveryCompanyCode(request.deliveryCompanyCode(), account.getPlatform());
        String path = (update ? coupangProperties.getUpdateInvoicesPath() : coupangProperties.getInvoicesPath())
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId());

        // 🔴 실물 박스 저장(PLAN 2609_40 D5 ①) — 사용자가 입력한 송장·택배사 코드로 그 박스의 행 1개.
        //    묶음이 앵커로 지정돼 있으므로 D30(다중 묶음 가드)과 무관하다. 이미 있으면 건너뛴다.
        //    전송 성공 여부와 무관하게, 전송 전에 남긴다 — 송장은 이미 발급됐다.
        try {
            shipmentParcelRecorder.record(anchor.getOrderShipment(), invoiceNumber, deliveryCompanyCode, null);
        } catch (Exception e) {
            log.warn("단건 발송처리 실물 박스 저장 실패(전송은 계속): orderId={} box={}", externalOrderId, boxId, e);
        }

        List<InvoiceLine> lines = toInvoiceLines(boxLines);
        AccountResult result;
        try {
            result = postInvoices(account, lines, orderId -> invoiceNumber, deliveryCompanyCode, path);
        } catch (Exception e) {
            // 일괄 경로의 계정 격리와 같은 형태로 실패 1건을 만든다(예외를 500 으로 새게 두지 않는다).
            log.warn("단건 발송처리 전송 실패: orderItemId={} box={} mode={}",
                    request.orderItemId(), boxId, mode, e);
            return new ManualShipmentResult(externalOrderId, boxId, mode,
                    lines.size(), 0, List.of(new FailedBox(boxId, "ERROR", e.getMessage())), null);
        }
        // 4) 신규 업로드 성공만 write-back(D4·D5). 수정 모드는 상태를 바꾸지 않는다.
        //    ⚠️ resultStatus 는 write-back 성공 여부와 무관하게 SHIPPED 다 — markDeparted 는 실패해도 삼킨다.
        //       클라이언트 표시가 다음 동기화로 수렴하는 쪽이, 성공을 실패로 보이게 하는 것보다 낫다.
        String resultStatus = null;
        if (!update && result.succeeded() > 0) {
            Map<String, List<OrderLine>> byBox = new LinkedHashMap<>();
            invoiceWriteBack.registerWriteBack(boxLines, byBox);
            invoiceWriteBack.markDeparted(result.succeededBoxIds(), byBox);
            resultStatus = InvoiceWriteBack.STATUS_SHIPPED.name();
        }
        log.info("단건 발송처리: orderId={} box={} mode={} lines={} succeeded={} failed={}",
                externalOrderId, boxId, mode,
                lines.size(), result.succeeded(), result.failed().size());
        return new ManualShipmentResult(externalOrderId, boxId, mode,
                lines.size(), result.succeeded(), result.failed(), resultStatus);
    }

    @Override
    public ReservedInvoiceResult sendReservedInvoices(List<ReservedInvoice> invoices,
                                                      Map<Long, List<OrderLine>> linesByShipment) {
        List<Long> succeeded = new ArrayList<>();
        Map<Long, String> failed = new LinkedHashMap<>();
        Map<Long, ReservedInvoice> byShipmentId = new LinkedHashMap<>();
        invoices.forEach(i -> byShipmentId.putIfAbsent(i.orderShipmentId(), i));

        // (계정, 택배사, 주문 안 순번) 묶음마다 1 POST. postInvoices 는 송장을 주문번호로 고른다 — 한 POST 에 같은 주문의
        // 배송 묶음이 둘 들어가면 둘째 묶음이 첫 묶음 송장으로 나간다(되돌릴 수 없다). 주문 안 순번으로 갈라 막는다.
        Map<String, List<Long>> shipmentIdsByGroup = new LinkedHashMap<>();
        Map<String, MarketplaceAccount> accountByGroup = new LinkedHashMap<>();
        Map<String, Integer> nthByOrderId = new HashMap<>();
        for (Long shipmentId : byShipmentId.keySet()) {
            List<OrderLine> lines = linesByShipment.get(shipmentId);
            MarketplaceAccount account = lines.get(0).getOrder().getMarketplaceAccount();
            ReservedInvoice reserved = byShipmentId.get(shipmentId);
            int nth = nthByOrderId.merge(reserved.externalOrderId(), 1, Integer::sum);
            String group = account.getId() + "|" + reserved.carrierCode() + "|" + nth;
            accountByGroup.putIfAbsent(group, account);
            shipmentIdsByGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(shipmentId);
        }

        for (Map.Entry<String, List<Long>> entry : shipmentIdsByGroup.entrySet()) {
            MarketplaceAccount account = accountByGroup.get(entry.getKey());
            List<Long> shipmentIds = entry.getValue();
            String carrierCode = byShipmentId.get(shipmentIds.get(0)).carrierCode();
            List<OrderLine> groupLines = new ArrayList<>();
            Map<String, String> invoiceByOrderId = new LinkedHashMap<>();
            Map<String, Long> shipmentIdByBoxId = new LinkedHashMap<>();
            for (Long shipmentId : shipmentIds) {
                ReservedInvoice reserved = byShipmentId.get(shipmentId);
                List<OrderLine> lines = linesByShipment.get(shipmentId);
                groupLines.addAll(lines);
                invoiceByOrderId.putIfAbsent(reserved.externalOrderId(), reserved.invoiceNumbers().get(0));
                shipmentIdByBoxId.put(InvoiceWriteBack.boxIdOf(lines.get(0)), shipmentId);
            }
            Map<String, List<OrderLine>> dbLinesByBoxId = new LinkedHashMap<>();
            invoiceWriteBack.registerWriteBack(groupLines, dbLinesByBoxId);
            try {
                String path = coupangProperties.getInvoicesPath()
                        .replace("{vendorId}", CoupangCredentials.of(account).getVendorId());
                AccountResult result = postInvoices(account, toInvoiceLines(groupLines), invoiceByOrderId::get,
                        carrierCode, path);
                invoiceWriteBack.markDeparted(result.succeededBoxIds(), dbLinesByBoxId);
                for (String boxId : result.succeededBoxIds()) {
                    Long shipmentId = shipmentIdByBoxId.get(boxId);
                    if (shipmentId != null) {
                        succeeded.add(shipmentId);
                    }
                }
                for (FailedBox box : result.failed()) {
                    Long shipmentId = shipmentIdByBoxId.get(box.shipmentBoxId());
                    if (shipmentId != null) {
                        failed.put(shipmentId, box.resultCode() + ": " + box.message());
                    }
                }
            } catch (Exception e) {
                log.warn("예약 송장 등록 계정 배치 실패: account={}", account.getId(), e);
                shipmentIds.forEach(id -> failed.put(id, e.getMessage()));
            }
        }
        return new ReservedInvoiceResult(succeeded, failed);
    }

    /**
     * DB 미매칭 주문을 쿠팡 단건 발주서 조회로 확정한다(PLAN 송장시트 D16).
     *
     * <p>동기화가 실패해 주문 라인이 비어도 시트만 있으면 발송처리가 되게 하는 안전망이다.
     * 후보 계정(활성 COUPANG)을 순회해 박스가 나오는 첫 계정을 그 주문의 계정으로 확정하고,
     * 그 계정을 다음 주문의 후보 맨 앞으로 옮긴다(한 파일은 대개 같은 계정).</p>
     *
     * <p>응답 {@code box.status} 가 배송지시 이상이면 그 박스를 제외하고, 남는 라인이 없으면
     * {@code skipped} 로 보고한다(PLAN 2609_07 D6).</p>
     *
     * <p>조회로 받아온 박스는 전송 여부와 무관하게 {@code order_line} 에 적재되고(PLAN 2609_13 D9),
     * 확정된 주문의 DB 라인은 {@code dbLinesByBoxId} 에 편입돼 성공 시 write-back 대상이 된다.</p>
     *
     * @return 폴백으로 확정된 주문 수(스킵분 제외). 실패한 주문은 {@code unmatched} 에 추가된다.
     */
    private int fallback(List<String> candidates, List<String> unmatched, List<SkippedOrder> skipped,
                         Map<Long, MarketplaceAccount> accountById,
                         Map<Long, List<InvoiceLine>> linesByAccount,
                         Map<String, List<OrderLine>> dbLinesByBoxId,
                         Map<String, List<String>> invoicesByOrderId,
                         Map<Platform, String> carrierCodeCache) {
        if (candidates.isEmpty()) {
            return 0;                       // 정상 경로: 쿠팡 호출 0회
        }

        List<MarketplaceAccount> coupangAccounts = new ArrayList<>(
                marketplaceAccountRepository.findByIsActiveTrue().stream()
                        .filter(a -> Platform.COUPANG.equals(a.getPlatform()))
                        .toList());
        if (coupangAccounts.isEmpty()) {
            unmatched.addAll(candidates);
            log.warn("발송처리 폴백 불가 — 활성 쿠팡 계정 없음: 미매칭 {}건", candidates.size());
            return 0;
        }

        int resolved = 0;
        int consecutiveFailures = 0;
        boolean circuitOpen = false;

        for (int i = 0; i < candidates.size(); i++) {
            String orderId = candidates.get(i);
            if (circuitOpen) {
                unmatched.add(orderId);
                continue;
            }
            if (i >= MAX_FALLBACK_ORDERS) {
                unmatched.add(orderId);
                continue;
            }

            FallbackHit hit = lookupOrder(orderId, coupangAccounts);
            if (hit == null) {
                unmatched.add(orderId);
                consecutiveFailures++;
                if (consecutiveFailures >= FALLBACK_CIRCUIT_THRESHOLD) {
                    circuitOpen = true;
                    log.warn("발송처리 폴백 중단 — 연속 {}건 전 계정 실패", consecutiveFailures);
                }
                continue;
            }

            if (hit.lines().isEmpty()) {              // 이 분기에서 skippedStatus 는 non-null 이 보장된다
                skipped.add(new SkippedOrder(orderId, hit.skippedStatus(),
                        ShipmentConfirmResult.ALREADY_SHIPPED_REASON));
                consecutiveFailures = 0;              // 응답은 정상이다 — 스킵은 실패가 아니다
                promoteToFront(coupangAccounts, hit.account());   // 계정은 확정됐다
                continue;                             // resolved 증가 없음(D8) · unmatched 에도 넣지 않는다
            }

            consecutiveFailures = 0;
            resolved++;
            MarketplaceAccount account = hit.account();
            accountById.putIfAbsent(account.getId(), account);
            linesByAccount.computeIfAbsent(account.getId(), k -> new ArrayList<>()).addAll(hit.lines());
            // 조회 시점에 적재했으므로(fetchOrderLines) 이 주문도 write-back 대상이 된다(PLAN 2609_13 D9).
            // upsertBox 가 자기 트랜잭션에서 커밋한 뒤라 여기서 읽으면 방금 저장한 라인이 보인다.
            // 전송에서 빠진 박스(DEPARTURE 이상)가 섞여도 무해하다 — markDeparted 는 쿠팡이 성공을
            // 돌려준 박스 id 만 갱신한다. 여기서 다시 거르면 폴백 경로에만 필터 규칙이 하나 더 생긴다.
            List<OrderLine> storedLines = orderLineRepository.findByExternalOrderId(orderId);
            invoiceWriteBack.registerWriteBack(storedLines, dbLinesByBoxId);
            // 🔴 폴백으로 확정된 주문도 같은 헬퍼를 부른다(PLAN 2609_40) — 빠뜨리면 그 주문만
            //    다음 주문 동기화 전까지 스캔되지 않는다.
            invoiceWriteBack.recordParcels(orderId, storedLines, invoicesByOrderId.get(orderId), carrierCodeCache);
            promoteToFront(coupangAccounts, account);
            log.info("발송처리 폴백 확정: orderId={} account={} boxes={}",
                    orderId, account.getId(), distinctBoxIds(hit.lines()).size());
        }

        int overLimit = candidates.size() - Math.min(candidates.size(), MAX_FALLBACK_ORDERS);
        if (overLimit > 0) {
            log.warn("발송처리 폴백 상한({}) 초과 — {}건은 조회 없이 미매칭 유지", MAX_FALLBACK_ORDERS, overLimit);
        }
        return resolved;
    }

    /** 다음 주문도 대개 같은 계정 → 후보 맨 앞으로. */
    private void promoteToFront(List<MarketplaceAccount> candidates, MarketplaceAccount account) {
        candidates.remove(account);
        candidates.add(0, account);
    }

    /**
     * 후보 계정을 순서대로 조회해 박스가 나오는 첫 계정을 반환. 전부 실패하면 null(그 주문만 미매칭).
     *
     * <p>라인이 0행이어도 상태로 걸러낸 것(={@code skippedStatus != null})이면 계정이 확정된 것이므로
     * 다음 계정을 시도하지 않고 즉시 반환한다. 전량취소 등 기존 사유의 0행은 예전처럼 다음 계정을 시도한다.</p>
     */
    private FallbackHit lookupOrder(String orderId, List<MarketplaceAccount> candidates) {
        for (MarketplaceAccount account : candidates) {
            try {
                OrderLookup lookup = fetchOrderLines(account, orderId);
                if (!lookup.lines().isEmpty() || lookup.skippedStatus() != null) {
                    return new FallbackHit(account, lookup.lines(), lookup.skippedStatus());
                }
            } catch (Exception e) {
                // 이 계정에서 실패해도 다음 계정을 시도한다(주문이 어느 계정 것인지 모르므로).
                log.warn("발송처리 폴백 조회 실패: orderId={} account={}", orderId, account.getId(), e);
            }
        }
        return null;
    }

    /**
     * Coupang single-order ordersheet lookup → invoice-upload lines.
     *
     * <p>⚠️ The path building and the envelope check duplicate {@code CoupangShippingLabelAdapter.collectOrderRows},
     * but they are not extracted — the verified sheet path keeps zero regression risk (the same call as shipping-label
     * PLAN D5).</p>
     * <p>⚠️ Cancelled lines are excluded <b>only on the fallback path</b> (the DB path keeps its behaviour — out of
     * scope). The rule is the same as the sheet's {@code flattenBox}: excluded when
     * {@code shippingCount - cancelCount <= 0}.</p>
     * <p>⚠️ A box at DEPARTURE or later is excluded and its status is kept as {@code skippedStatus} (PLAN 2609_07
     * D6) — it must stay distinct from the zero rows of a fully cancelled order.</p>
     * <p>⚠️ Fetched boxes are loaded into {@code order_line} <b>whether skipped or not</b> (PLAN 2609_13 D9) — the
     * point of this save is that the next shipment confirm does not take the fallback. No extra API call.</p>
     */
    private OrderLookup fetchOrderLines(MarketplaceAccount account, String orderId) {
        String path = coupangProperties.getOrdersheetByOrderPath()
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId())
                .replace("{orderId}", orderId);

        JsonNode parsed = readTree(coupangApiClient.get(path, "", account));
        JsonNode data = parsed.path("data");
        if (parsed.path("code").asInt(200) != 200 || !data.isArray()) {
            // 잘못된 orderId 에도 200 + 비정상 봉투가 올 수 있어 "0행"과 "실패"를 여기서 분리한다.
            throw new IllegalStateException("쿠팡 발주서 단건 응답 이상: code=" + parsed.path("code").asText());
        }

        List<InvoiceLine> lines = new ArrayList<>();
        String skippedStatus = null;
        for (JsonNode box : data) {
            String boxStatus = box.path("status").asText("");
            // 조회 응답은 이미 정확하다 — 전송 대상에서 빠지는 박스도 저장한다(PLAN 2609_13 D9).
            // ⚠️ 전송이 우선이다(D6). 저장 실패가 발송처리를 막으면 안 되므로 박스 단위로 삼킨다(D4·D5).
            try {
                orderUpserter.upsertBox(account, box);
            } catch (Exception e) {
                log.warn("발송처리 폴백 주문 적재 실패(전송은 계속): orderId={} account={}",
                        orderId, account.getId(), e);
            }
            // 원문 → 중립 상태로 옮겨 같은 블랙리스트로 판정한다. 매핑 실패(모르는 코드)는 전송한다.
            OrderStatus mapped = OrderStatus.fromCoupang(boxStatus).orElse(null);
            if (mapped != null && SKIP_STATUSES.contains(mapped)) {
                if (skippedStatus == null) {
                    skippedStatus = mapped.name();              // 첫 번째로 걸러진 박스의 상태만 기억
                }
                continue;                                       // 이미 발송된 박스는 전송하지 않는다
            }
            String boxId = box.path("shipmentBoxId").asText("");
            for (JsonNode item : box.path("orderItems")) {
                int quantity = item.path("shippingCount").asInt(0) - item.path("cancelCount").asInt(0);
                if (quantity <= 0) {
                    continue;                                   // 전량 확정취소 라인은 미발송
                }
                lines.add(new InvoiceLine(boxId, orderId, item.path("vendorItemId").asText("")));
            }
        }
        return new OrderLookup(lines, skippedStatus);
    }

    /**
     * 계정의 (박스×라인)을 dto/라인 으로 조립해 1 POST 전송하고 응답을 집계.
     *
     * <p>🔴 송장이 여러 장인 주문도 <b>대표 1장(첫 송장)만</b> 보낸다(PLAN 2609_40 D8) — 쿠팡은 배송 묶음
     * 하나에 송장 하나만 받는다. 나머지 송장은 실물 박스({@code shipment_parcel})에만 남는다.
     */
    private AccountResult sendBatch(MarketplaceAccount account, List<InvoiceLine> lines,
                                    Map<String, List<String>> invoicesByOrderId) throws Exception {
        // deliveryCompanyCode 는 계정당 1회 (하드코딩 금지, 미설정 시 IllegalStateException).
        String deliveryCompanyCode = carrierCodeService.resolveDeliveryCompanyCode(account.getPlatform());
        String path = coupangProperties.getInvoicesPath()
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId());
        return postInvoices(account, lines, orderId -> InvoiceWriteBack.representativeInvoice(invoicesByOrderId, orderId),
                deliveryCompanyCode, path);
    }

    /**
     * dto 조립 → 1 POST → responseList 집계. 일괄(xlsx)·단건 공용 (PLAN 2609_11 D10).
     * 경로만 다르면 송장수정도 같은 바디다(dto 구조 동일).
     *
     * ⚠️ 이름을 {@code post} 로 짓지 말 것 — 본문에서 {@code coupangApiClient.post(...)} 와 섞여 읽힌다.
     */
    private AccountResult postInvoices(MarketplaceAccount account, List<InvoiceLine> lines,
                                       Function<String, String> invoiceResolver,
                                       String deliveryCompanyCode, String path) throws Exception {
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (InvoiceLine line : lines) {
            Map<String, Object> dto = new LinkedHashMap<>();
            // external*Id 는 String → 요청 바디는 long 으로 변환.
            dto.put("shipmentBoxId", Long.parseLong(line.shipmentBoxId()));
            dto.put("orderId", Long.parseLong(line.orderId()));
            dto.put("deliveryCompanyCode", deliveryCompanyCode);
            dto.put("invoiceNumber", invoiceResolver.apply(line.orderId()));
            dto.put("vendorItemId", Long.parseLong(line.vendorItemId()));
            dto.put("splitShipping", false);
            dto.put("preSplitShipped", false);
            dto.put("estimatedShippingDate", "");
            dtos.add(dto);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendorId", CoupangCredentials.of(account).getVendorId());
        body.put("orderSheetInvoiceApplyDtos", dtos);

        String json = objectMapper.writeValueAsString(body);
        String response = coupangApiClient.post(path, json, account);

        return parseResponse(response);
    }

    /** 응답 data.responseList 집계. data/responseList 없으면 예외(→ 계정 격리). */
    private AccountResult parseResponse(String json) {
        JsonNode data = readTree(json).path("data");
        if (data.isMissingNode() || data.path("responseList").isMissingNode()) {
            throw new IllegalStateException("쿠팡 송장업로드 응답 파싱 실패");
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
     * DB 라인(order_line) → 송장업로드 라인.
     *
     * <p>{@code vendorItemId} 는 core 가 아니라 쿠팡 거울({@code coupang_order_line})에 있으므로
     * 라인 id 묶음으로 <b>한 번에</b> 읽는다(FEATURE_2609_26 / 04 §3-3).
     * 거울 행이 없는 라인은 전송 식별자를 만들 수 없어 제외한다(쿠팡 주문에는 항상 있다).
     */
    private List<InvoiceLine> toInvoiceLines(List<OrderLine> lines) {
        Map<Long, CoupangOrderLine> mirrors = mirrorsOf(lines);
        List<InvoiceLine> invoiceLines = new ArrayList<>();
        for (OrderLine line : lines) {
            CoupangOrderLine mirror = mirrors.get(line.getId());
            if (mirror == null) {
                log.warn("발송처리 라인 제외 — 쿠팡 거울 행 없음: line={}", line.getId());
                continue;
            }
            invoiceLines.add(new InvoiceLine(InvoiceWriteBack.boxIdOf(line),
                    line.getOrder().getExternalOrderId(), mirror.getVendorItemId()));
        }
        return invoiceLines;
    }

    /** 라인 id → 쿠팡 거울 행. 빈 목록으로 IN 을 날리지 않는다. */
    private Map<Long, CoupangOrderLine> mirrorsOf(List<OrderLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = lines.stream().map(OrderLine::getId).toList();
        return coupangOrderLineRepository.findByOrderLine_IdIn(ids).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
    }

    /** 블랙리스트 판정 — 상태를 모르면(null) 전송한다(D1). */
    private boolean sendable(OrderLine line) {
        return line.getStatus() == null || !SKIP_STATUSES.contains(line.getStatus());
    }

    private List<String> distinctBoxIds(List<InvoiceLine> lines) {
        return lines.stream().map(InvoiceLine::shipmentBoxId).distinct().toList();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 송장업로드 응답 파싱 실패", e);
        }
    }

    /** 송장업로드 dto 1건에 필요한 최소 식별자(DB 라인·쿠팡 폴백 라인 공통). */
    private record InvoiceLine(String shipmentBoxId, String orderId, String vendorItemId) {
    }

    /**
     * 폴백 단건조회 결과. lines 가 빈 이유를 반드시 구분한다:
     * <ul>
     *   <li>{@code skippedStatus != null} → 상태로 걸러냄(이미 발송) = 스킵 보고 대상</li>
     *   <li>{@code skippedStatus == null} → 전량취소(shippingCount-cancelCount&lt;=0) 등 기존 사유 = 기존과 동일하게 0행 취급</li>
     * </ul>
     */
    private record OrderLookup(List<InvoiceLine> lines, String skippedStatus) {
    }

    /** 폴백으로 확정된 주문의 계정과 라인(라인이 비면 상태 스킵 — skippedStatus 참고). */
    private record FallbackHit(MarketplaceAccount account, List<InvoiceLine> lines, String skippedStatus) {
    }

    /**
     * 계정 배치 결과. succeeded 는 응답 건수 그대로, succeededBoxIds 는 write-back 가능한(공백 아닌) 박스만.
     * ⚠️ 합포장은 같은 shipmentBoxId 로 dto N개를 보내므로 responseList 도 박스당 N행일 수 있다
     *    → succeededBoxIds 는 <b>distinct</b> (기존 실패 경로가 distinctBoxIds() 를 쓰는 것과 같은 이유).
     */
    private record AccountResult(int succeeded, List<String> succeededBoxIds, List<FailedBox> failed) {
    }
}
