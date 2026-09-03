package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangOrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ShipmentConfirmService} 구현 — COUPANG 전용 발송처리 레그.
 *
 * 흐름: xlsx 파싱 → {@code findByExternalOrderId} 로 order_item 전개 →
 * 라인(박스) 상태 필터(배송지시 이상은 전송하지 않고 skipped 로 분류, PLAN 2609_07 D1·D2) →
 * (DB 미매칭분은 쿠팡 단건 조회 폴백 — 응답 박스 상태에도 같은 필터, D6) →
 * 계정(id) 그룹핑 → 계정별 1 POST(합포장은 같은 shipmentBoxId·invoiceNumber, vendorItemId 만 다름) →
 * responseList 집계 → 성공 박스의 로컬 status 를 DEPARTURE 로 write-back(D4).
 *
 * ⚠️ 계정 단위 try/catch 로 한 계정 실패(택배사코드 미설정 IllegalStateException·전송·파싱 오류 포함)를 격리한다
 *    — 다른 계정 배치는 계속. 네이버 등 비-COUPANG 은 unmatched 로 리포트(후속 어댑터 스코프).
 * ⚠️ 폴백(PLAN 송장시트 D16)은 동기화가 실패해 order_item 이 비어도 시트로 발송처리가 되게 하는 안전망이다
 *    — 정상 경로(DB 매칭)에서는 쿠팡을 한 번도 호출하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentConfirmServiceImpl implements ShipmentConfirmService {

    private static final String PLATFORM_COUPANG = "COUPANG";
    private static final int COL_ORDER_ID = 5;    // 주문번호
    private static final int COL_INVOICE = 6;      // 운송장번호

    /** 폴백 대상 주문 수 상한. 초과분은 조회하지 않고 미매칭으로 남긴다(쿠팡 호출 폭주 방지). */
    private static final int MAX_FALLBACK_ORDERS = 50;
    /** 연속 이 횟수만큼 후보 계정 전부에서 실패하면 폴백을 전면 중단한다(쿠팡 장애 시 스레드 점유 방지). */
    private static final int FALLBACK_CIRCUIT_THRESHOLD = 3;

    /**
     * 전송하지 않을 상태 — 이 상태는 되돌아오지 않으므로(상태 단조성) 조회 없이 제외해도 누락이 불가능하다.
     * ⚠️ 화이트리스트가 아니라 블랙리스트다: 모르는 상태값은 <b>전송</b>한다
     *    (조용한 스킵 = 발송 누락, 전송 = 쿠팡이 판단). PLAN 2609_07 D1.
     */
    private static final Set<String> SKIP_STATUSES = Set.of(
            CoupangOrderStatus.DEPARTURE.name(),
            CoupangOrderStatus.DELIVERING.name(),
            CoupangOrderStatus.FINAL_DELIVERY.name(),
            CoupangOrderStatus.NONE_TRACKING.name());
    private static final String STATUS_DEPARTURE = CoupangOrderStatus.DEPARTURE.name();

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderItemRepository orderItemRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CarrierCodeService carrierCodeService;
    private final ObjectMapper objectMapper;

    @Override
    public ShipmentConfirmResult confirm(MultipartFile file) {
        List<UploadRow> uploadRows = parse(file);

        // orderId → invoiceNumber (1주문=1박스 전제, 중복 시 첫 행 사용).
        Map<String, String> invoiceByOrderId = new LinkedHashMap<>();
        for (UploadRow row : uploadRows) {
            // 한 주문에 상품이 여러 개면 결과 파일에 같은 송장번호로 N행이 오는 게 정상이다 → 값이 다를 때만 경고.
            String prev = invoiceByOrderId.putIfAbsent(row.orderId(), row.invoiceNumber());
            if (prev != null && !prev.equals(row.invoiceNumber())) {
                log.warn("발송처리 주문번호당 송장번호 불일치 — 첫 행 사용: orderId={} 사용={} 무시={}",
                        row.orderId(), prev, row.invoiceNumber());
            }
        }

        List<String> unmatched = new ArrayList<>();
        List<SkippedOrder> skipped = new ArrayList<>();
        // DB 에 아예 없는 주문만 폴백 대상. 비-COUPANG 은 여기 넣지 않는다
        // (네이버 주문을 쿠팡에 조회하게 되므로 unmatched 로만 유지).
        List<String> fallbackCandidates = new ArrayList<>();
        Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        Map<Long, List<InvoiceLine>> linesByAccount = new LinkedHashMap<>();
        // 송장업로드 성공 시 status 를 갱신할 DB 라인(박스 단위). 폴백으로만 잡힌 박스는 여기 없다.
        Map<String, List<OrderItem>> dbLinesByBoxId = new LinkedHashMap<>();
        int matchedOrders = 0;

        for (String orderId : invoiceByOrderId.keySet()) {
            List<OrderItem> lines = orderItemRepository.findByExternalOrderId(orderId);
            if (lines.isEmpty()) {
                fallbackCandidates.add(orderId);
                continue;
            }
            // 1주문=1박스 → 라인들은 같은 계정. 비-COUPANG 은 unmatched 로 스킵(resolve/post 미호출).
            MarketplaceAccount account = lines.get(0).getMarketplaceAccount();
            if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
                unmatched.add(orderId);
                continue;
            }
            // 한 주문에 박스가 여러 개면 박스마다 status 가 다를 수 있다(order_item.status = box.status).
            List<OrderItem> sendable = lines.stream()
                    .filter(l -> !SKIP_STATUSES.contains(l.getStatus()))
                    .toList();
            if (sendable.isEmpty()) {
                // 전량 발송 완료 — 쿠팡 호출 0회. unmatched 가 아니다(계정도 order_item 도 확정돼 있다).
                skipped.add(new SkippedOrder(orderId, lines.get(0).getStatus()));
                continue;
            }
            matchedOrders++;
            accountById.putIfAbsent(account.getId(), account);
            linesByAccount.computeIfAbsent(account.getId(), k -> new ArrayList<>())
                    .addAll(toInvoiceLines(sendable));
            registerWriteBack(sendable, dbLinesByBoxId);
        }

        matchedOrders += fallback(fallbackCandidates, unmatched, skipped, accountById, linesByAccount);

        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        for (Map.Entry<Long, List<InvoiceLine>> entry : linesByAccount.entrySet()) {
            MarketplaceAccount account = accountById.get(entry.getKey());
            List<InvoiceLine> lines = entry.getValue();
            try {
                AccountResult result = sendBatch(account, lines, invoiceByOrderId);
                succeeded += result.succeeded();
                markDeparted(result.succeededBoxIds(), dbLinesByBoxId);
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

        log.info("발송처리 결과: rows={} matched={} skipped={} unmatched={} succeeded={} failed={}",
                uploadRows.size(), matchedOrders, skipped.size(), unmatched.size(), succeeded, failed.size());
        if (!skipped.isEmpty()) {
            // 정상 동작이라 info — 실패/미매칭(warn)과 구분한다.
            log.info("발송처리 스킵(이미 발송됨) {}건: {}", skipped.size(), skipped);
        }
        if (!unmatched.isEmpty()) {
            log.warn("발송처리 미매칭 주문번호 {}건: {}", unmatched.size(), unmatched);
        }
        for (FailedBox f : failed) {
            log.warn("발송처리 박스 실패: boxId={} code={} msg={}", f.shipmentBoxId(), f.resultCode(), f.message());
        }

        return new ShipmentConfirmResult(uploadRows.size(), matchedOrders, unmatched, succeeded, failed, skipped);
    }

    /**
     * 송장업로드에 성공한 박스의 로컬 status 를 DEPARTURE 로 맞춘다(PLAN 2609_07 D4).
     *
     * <p>재업로드 시 상태 필터가 즉시 걸러내고, 주문목록도 동기화 없이 바로 "배송지시"로 보인다.
     * 다음 주문동기화가 쿠팡 값으로 덮어써도 같은 값이라 충돌하지 않는다.</p>
     *
     * ⚠️ 여기서 던지면 안 된다 — 쿠팡 전송은 이미 성공했고, 예외가 계정 격리 catch 에 걸리면
     *    성공한 박스가 failed 로 보고돼 사용자가 재업로드(중복 전송)하게 된다(PLAN 2609_07 D5).
     *    → saveAll 뿐 아니라 <b>본문 전체</b>를 try 로 감싼다(엔티티 조립 중 예외도 새어나가면 안 된다).
     */
    private void markDeparted(List<String> succeededBoxIds, Map<String, List<OrderItem>> dbLinesByBoxId) {
        try {
            List<OrderItem> updated = new ArrayList<>();
            for (String boxId : succeededBoxIds) {
                for (OrderItem line : dbLinesByBoxId.getOrDefault(boxId, List.of())) {
                    updated.add(line.toBuilder().status(STATUS_DEPARTURE).build());   // 동기화 upsert 와 같은 패턴
                }
            }
            if (updated.isEmpty()) {
                return;                               // 폴백으로만 전송된 주문 = DB 행 없음
            }
            orderItemRepository.saveAll(updated);
        } catch (Exception e) {
            log.warn("발송처리 로컬 상태 갱신 실패(쿠팡 전송은 성공): boxes={}", succeededBoxIds, e);
        }
    }

    /**
     * 성공 시 write-back 할 DB 라인을 박스 id 로 색인한다.
     *
     * <p>{@code externalBoxId} 는 nullable 이므로 널/공백 키는 담지 않는다. DB 값과 응답 {@code shipmentBoxId} 는
     * 같은 쿠팡 id 의 문자열이므로(동기화가 {@code box.path("shipmentBoxId").asText()} 로 넣은 값)
     * 숫자 변환·패딩 없이 문자열 그대로 비교한다.</p>
     */
    private void registerWriteBack(List<OrderItem> lines, Map<String, List<OrderItem>> target) {
        for (OrderItem line : lines) {
            String boxId = line.getExternalBoxId();
            if (boxId == null || boxId.isBlank()) {
                continue;
            }
            target.computeIfAbsent(boxId, k -> new ArrayList<>()).add(line);
        }
    }

    /**
     * DB 미매칭 주문을 쿠팡 단건 발주서 조회로 확정한다(PLAN 송장시트 D16).
     *
     * <p>동기화가 실패해 order_item 이 비어도 시트만 있으면 발송처리가 되게 하는 안전망이다.
     * 후보 계정(활성 COUPANG)을 순회해 박스가 나오는 첫 계정을 그 주문의 계정으로 확정하고,
     * 그 계정을 다음 주문의 후보 맨 앞으로 옮긴다(한 파일은 대개 같은 계정).</p>
     *
     * <p>응답 {@code box.status} 가 배송지시 이상이면 그 박스를 제외하고, 남는 라인이 없으면
     * {@code skipped} 로 보고한다(PLAN 2609_07 D6).</p>
     *
     * @return 폴백으로 확정된 주문 수(스킵분 제외). 실패한 주문은 {@code unmatched} 에 추가된다.
     */
    private int fallback(List<String> candidates, List<String> unmatched, List<SkippedOrder> skipped,
                         Map<Long, MarketplaceAccount> accountById,
                         Map<Long, List<InvoiceLine>> linesByAccount) {
        if (candidates.isEmpty()) {
            return 0;                       // 정상 경로: 쿠팡 호출 0회
        }

        List<MarketplaceAccount> coupangAccounts = new ArrayList<>(
                marketplaceAccountRepository.findByIsActiveTrue().stream()
                        .filter(a -> PLATFORM_COUPANG.equals(a.getPlatform()))
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
                skipped.add(new SkippedOrder(orderId, hit.skippedStatus()));
                consecutiveFailures = 0;              // 응답은 정상이다 — 스킵은 실패가 아니다
                promoteToFront(coupangAccounts, hit.account());   // 계정은 확정됐다
                continue;                             // resolved 증가 없음(D8) · unmatched 에도 넣지 않는다
            }

            consecutiveFailures = 0;
            resolved++;
            MarketplaceAccount account = hit.account();
            accountById.putIfAbsent(account.getId(), account);
            linesByAccount.computeIfAbsent(account.getId(), k -> new ArrayList<>()).addAll(hit.lines());
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
     * 쿠팡 단건 발주서 조회 → 송장업로드 라인 추출.
     *
     * <p>⚠️ 경로 조립·봉투 검사가 {@code ShippingLabelServiceImpl.previewRowsByOrder} 와 중복이지만
     * 공통 추출하지 않는다 — 검증이 끝난 시트 경로의 회귀 위험을 0으로 유지한다(PLAN 송장시트 D5 와 같은 판단).</p>
     * <p>⚠️ 취소 라인 제외는 <b>폴백 경로에만</b> 있다(DB 경로는 기존 동작 유지 — 이번 스코프 아님).
     * 규칙은 시트 생성 {@code flattenBox} 와 동일: {@code shippingCount - cancelCount <= 0} 이면 제외.</p>
     * <p>⚠️ 배송지시 이상 박스는 제외하고 그 상태를 {@code skippedStatus} 로 남긴다(PLAN 2609_07 D6) —
     * 전량취소로 0행이 된 경우와 반드시 구분된다.</p>
     */
    private OrderLookup fetchOrderLines(MarketplaceAccount account, String orderId) {
        String path = coupangProperties.getOrdersheetByOrderPath()
                .replace("{vendorId}", account.getVendorId())
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
            if (SKIP_STATUSES.contains(boxStatus)) {
                if (skippedStatus == null) {
                    skippedStatus = boxStatus;                  // 첫 번째로 걸러진 박스의 상태만 기억
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

    /** 계정의 (박스×라인)을 dto/라인 으로 조립해 1 POST 전송하고 응답을 집계. */
    private AccountResult sendBatch(MarketplaceAccount account, List<InvoiceLine> lines,
                                    Map<String, String> invoiceByOrderId) throws Exception {
        // deliveryCompanyCode 는 계정당 1회 (하드코딩 금지, 미설정 시 IllegalStateException).
        String deliveryCompanyCode = carrierCodeService.resolveDeliveryCompanyCode(account.getPlatform());

        List<Map<String, Object>> dtos = new ArrayList<>();
        for (InvoiceLine line : lines) {
            Map<String, Object> dto = new LinkedHashMap<>();
            // external*Id 는 String → 요청 바디는 long 으로 변환.
            dto.put("shipmentBoxId", Long.parseLong(line.shipmentBoxId()));
            dto.put("orderId", Long.parseLong(line.orderId()));
            dto.put("deliveryCompanyCode", deliveryCompanyCode);
            dto.put("invoiceNumber", invoiceByOrderId.get(line.orderId()));
            dto.put("vendorItemId", Long.parseLong(line.vendorItemId()));
            dto.put("splitShipping", false);
            dto.put("preSplitShipped", false);
            dto.put("estimatedShippingDate", "");
            dtos.add(dto);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendorId", account.getVendorId());
        body.put("orderSheetInvoiceApplyDtos", dtos);

        String json = objectMapper.writeValueAsString(body);
        String path = coupangProperties.getInvoicesPath().replace("{vendorId}", account.getVendorId());
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

    /** 택배사 결과 xlsx 파싱 → 주문번호(5)·운송장번호(6)만 사용, 공백행 스킵. */
    private List<UploadRow> parse(MultipartFile file) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<UploadRow> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String orderId = cellString(row.getCell(COL_ORDER_ID), formatter);
                String invoiceNumber = cellString(row.getCell(COL_INVOICE), formatter);
                if (orderId.isBlank() || invoiceNumber.isBlank()) {
                    continue;                       // 주문번호/운송장번호 중 하나라도 공백이면 스킵
                }
                rows.add(new UploadRow(orderId, invoiceNumber));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalArgumentException("발송처리 파일 파싱 실패", e);
        }
    }

    /** 셀을 문자열로 읽되 숫자셀은 지수표기로 깨지지 않게 long 으로 읽는다. */
    private String cellString(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return String.valueOf((long) cell.getNumericCellValue());
        }
        return formatter.formatCellValue(cell).trim();
    }

    /** DB 라인(order_item) → 송장업로드 라인. */
    private List<InvoiceLine> toInvoiceLines(List<OrderItem> lines) {
        return lines.stream()
                .map(l -> new InvoiceLine(l.getExternalBoxId(), l.getExternalOrderId(), l.getExternalItemId()))
                .toList();
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

    /** 업로드 xlsx 한 행에서 사용하는 두 열. */
    private record UploadRow(String orderId, String invoiceNumber) {
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
