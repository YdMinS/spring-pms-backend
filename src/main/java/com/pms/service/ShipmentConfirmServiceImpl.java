package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderItemRepository;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.coupang.CoupangApiClient;
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

/**
 * {@link ShipmentConfirmService} 구현 — COUPANG 전용 발송처리 레그.
 *
 * 흐름: xlsx 파싱 → {@code findByExternalOrderId} 로 order_item 전개 → 계정(id) 그룹핑 →
 * 계정별 1 POST(합포장은 같은 shipmentBoxId·invoiceNumber, vendorItemId 만 다름) → responseList 집계.
 *
 * ⚠️ 계정 단위 try/catch 로 한 계정 실패(택배사코드 미설정 IllegalStateException·전송·파싱 오류 포함)를 격리한다
 *    — 다른 계정 배치는 계속. 네이버 등 비-COUPANG 은 unmatched 로 리포트(후속 어댑터 스코프).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentConfirmServiceImpl implements ShipmentConfirmService {

    private static final String PLATFORM_COUPANG = "COUPANG";
    private static final int COL_ORDER_ID = 5;    // 주문번호
    private static final int COL_INVOICE = 6;      // 운송장번호

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final OrderItemRepository orderItemRepository;
    private final CarrierCodeService carrierCodeService;
    private final ObjectMapper objectMapper;

    @Override
    public ShipmentConfirmResult confirm(MultipartFile file) {
        List<UploadRow> uploadRows = parse(file);

        // orderId → invoiceNumber (1주문=1박스 전제, 중복 시 첫 행 사용).
        Map<String, String> invoiceByOrderId = new LinkedHashMap<>();
        for (UploadRow row : uploadRows) {
            if (invoiceByOrderId.putIfAbsent(row.orderId(), row.invoiceNumber()) != null) {
                log.warn("발송처리 중복 주문번호 — 첫 행 사용: orderId={}", row.orderId());
            }
        }

        List<String> unmatched = new ArrayList<>();
        Map<Long, MarketplaceAccount> accountById = new LinkedHashMap<>();
        Map<Long, List<OrderItem>> linesByAccount = new LinkedHashMap<>();
        int matchedOrders = 0;

        for (String orderId : invoiceByOrderId.keySet()) {
            List<OrderItem> lines = orderItemRepository.findByExternalOrderId(orderId);
            if (lines.isEmpty()) {
                unmatched.add(orderId);
                continue;
            }
            // 1주문=1박스 → 라인들은 같은 계정. 비-COUPANG 은 unmatched 로 스킵(resolve/post 미호출).
            MarketplaceAccount account = lines.get(0).getMarketplaceAccount();
            if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
                unmatched.add(orderId);
                continue;
            }
            matchedOrders++;
            accountById.putIfAbsent(account.getId(), account);
            linesByAccount.computeIfAbsent(account.getId(), k -> new ArrayList<>()).addAll(lines);
        }

        int succeeded = 0;
        List<FailedBox> failed = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItem>> entry : linesByAccount.entrySet()) {
            MarketplaceAccount account = accountById.get(entry.getKey());
            List<OrderItem> lines = entry.getValue();
            try {
                AccountResult result = sendBatch(account, lines, invoiceByOrderId);
                succeeded += result.succeeded();
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

        return new ShipmentConfirmResult(uploadRows.size(), matchedOrders, unmatched, succeeded, failed);
    }

    /** 계정의 (박스×라인)을 dto/라인 으로 조립해 1 POST 전송하고 응답을 집계. */
    private AccountResult sendBatch(MarketplaceAccount account, List<OrderItem> lines,
                                    Map<String, String> invoiceByOrderId) throws Exception {
        // deliveryCompanyCode 는 계정당 1회 (하드코딩 금지, 미설정 시 IllegalStateException).
        String deliveryCompanyCode = carrierCodeService.resolveDeliveryCompanyCode(account.getPlatform());

        List<Map<String, Object>> dtos = new ArrayList<>();
        for (OrderItem line : lines) {
            Map<String, Object> dto = new LinkedHashMap<>();
            // external*Id 는 String → 요청 바디는 long 으로 변환.
            dto.put("shipmentBoxId", Long.parseLong(line.getExternalBoxId()));
            dto.put("orderId", Long.parseLong(line.getExternalOrderId()));
            dto.put("deliveryCompanyCode", deliveryCompanyCode);
            dto.put("invoiceNumber", invoiceByOrderId.get(line.getExternalOrderId()));
            dto.put("vendorItemId", Long.parseLong(line.getExternalItemId()));
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
        List<FailedBox> failed = new ArrayList<>();
        for (JsonNode r : data.path("responseList")) {
            if (r.path("succeed").asBoolean(false)) {
                succeeded++;
            } else {
                failed.add(new FailedBox(
                        r.path("shipmentBoxId").asText(""),
                        r.path("resultCode").asText(""),
                        r.path("resultMessage").asText("")));
            }
        }
        return new AccountResult(succeeded, failed);
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

    private List<String> distinctBoxIds(List<OrderItem> lines) {
        return lines.stream().map(OrderItem::getExternalBoxId).distinct().toList();
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

    /** 계정 배치 전송 결과(성공 박스 수 + 실패 상세). */
    private record AccountResult(int succeeded, List<FailedBox> failed) {
    }
}
