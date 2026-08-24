package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⚠️ TEMPORARY 진단 전용 — 특정 주문의 취소가 우리 DB/쿠팡에서 어떻게 보이는지 원인 규명용.
 *
 * "판매자 취소했는데 상품준비중(INSTRUCT) 고착" 원인 확정을 위해 온디맨드로:
 *   1) 우리 order_item 행 상태
 *   2) 쿠팡 returnRequests(cancelType=CANCEL) 응답에 이 orderId 가 오는가 + returnItems 실제 필드 구조
 *   3) 쿠팡 단건 주문서(box) 현재 상태(status/cancel/hold)
 * 를 한 번에 조회해 반환한다. 원인 확정 후 이 클래스/컨트롤러는 제거한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangCancelDiagnosticService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_PER_PAGE = 50;
    private static final int MAX_PAGES = 40;             // 무한루프 가드
    private static final int LOOKBACK_DAYS = 30;         // 쿠팡 상한 < 31일

    private final OrderItemRepository orderItemRepository;
    private final CoupangProperties coupangProperties;
    private final CoupangApiClient coupangApiClient;
    private final ObjectMapper objectMapper;

    public Map<String, Object> diagnose(String orderId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);

        // @EntityGraph(marketplaceAccount) 로 account eager fetch → LAZY 없이 vendorId/keys 접근.
        List<OrderItem> rows = orderItemRepository.findByExternalOrderId(orderId);
        out.put("ourRows", rows.stream().map(this::rowView).toList());
        if (rows.isEmpty()) {
            out.put("note", "order_item 에 이 orderId 행이 없음 (동기화된 적 없음)");
            return out;
        }

        MarketplaceAccount account = rows.get(0).getMarketplaceAccount();
        // 이 주문의 취소/반품 기록이 returnRequests 에 존재하는지 2가지로 확인한다.
        // (RETURN=배송후 반품이라 상품준비중 취소엔 무관 → 제외. 날짜범위는 항상 필수.)
        out.put("returnRequests_CANCEL", probe(account, orderId, windowQuery("cancelType=CANCEL")));
        out.put("returnRequests_byOrderId", probe(account, orderId, windowQuery("orderId=" + orderId)));
        out.put("singleOrdersheets", probeSingleBoxes(account, rows));
        return out;
    }

    /** 최근 LOOKBACK_DAYS 창 + 주어진 필터로 returnRequests 기본 쿼리 조립. */
    private String windowQuery(String filter) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(LOOKBACK_DAYS);
        return filter
                + "&createdAtFrom=" + from.format(DATE)
                + "&createdAtTo=" + to.format(DATE)
                + "&maxPerPage=" + MAX_PER_PAGE;
    }

    private Map<String, Object> rowView(OrderItem o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("boxId", o.getExternalBoxId());
        m.put("itemId", o.getExternalItemId());
        m.put("status", o.getStatus());
        m.put("orderCount", o.getOrderCount());
        m.put("cancelCount", o.getCancelCount());
        m.put("holdCount", o.getHoldCount());
        m.put("vendorId", o.getMarketplaceAccount().getVendorId());
        return m;
    }

    /** 주어진 returnRequests 쿼리를 페이징하며 이 orderId 매칭 receipt + 구조를 수집. */
    private Map<String, Object> probe(MarketplaceAccount account, String orderId, String baseQuery) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", baseQuery);
        String path = coupangProperties.getReturnrequestsPath().replace("{vendorId}", account.getVendorId());

        List<JsonNode> matching = new ArrayList<>();
        List<String> allOrderIds = new ArrayList<>();
        List<String> receiptKeysSample = null;
        List<String> returnItemKeysSample = null;
        int totalReceipts = 0;
        int pages = 0;
        String nextToken = null;

        try {
            do {
                String query = (nextToken == null || nextToken.isBlank())
                        ? baseQuery : baseQuery + "&nextToken=" + nextToken;
                JsonNode parsed = objectMapper.readTree(coupangApiClient.get(path, query, account));
                pages++;

                for (JsonNode receipt : parsed.path("data")) {
                    totalReceipts++;
                    String rid = receipt.path("orderId").asText("");
                    allOrderIds.add(rid);
                    if (receiptKeysSample == null) {
                        receiptKeysSample = fieldNames(receipt);
                        JsonNode firstItem = receipt.path("returnItems").path(0);
                        if (!firstItem.isMissingNode()) {
                            returnItemKeysSample = fieldNames(firstItem);
                        }
                    }
                    if (orderId.equals(rid)) {
                        matching.add(receipt);
                    }
                }
                String prev = nextToken;
                nextToken = parsed.path("nextToken").asText("");
                if (nextToken.equals(prev) || pages >= MAX_PAGES) break;
            } while (nextToken != null && !nextToken.isBlank());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        result.put("pages", pages);
        result.put("totalReceipts", totalReceipts);
        result.put("receiptFieldNamesSample", receiptKeysSample);
        result.put("returnItemFieldNamesSample", returnItemKeysSample);
        result.put("matchingThisOrder", matching);          // 이 orderId 매칭 receipt 전체(returnItems 포함)
        result.put("allOrderIdsInWindow", allOrderIds);      // 창 내 취소 orderId 목록 (이 주문 포함 여부)
        return result;
    }

    /** order_item 의 각 box 를 쿠팡 단건 주문서로 조회해 현재 status/취소 상태 확인. */
    private List<Map<String, Object>> probeSingleBoxes(MarketplaceAccount account, List<OrderItem> rows) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (OrderItem o : rows) {
            String boxId = o.getExternalBoxId();
            if (boxId == null || boxId.isBlank() || seen.contains(boxId)) continue;
            seen.add(boxId);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("boxId", boxId);
            // Coupang 단건(shipmentBox) 주문서 조회 (v4). 경로가 다르면 error 로 드러난다.
            String path = "/v2/providers/openapi/apis/api/v4/vendors/" + account.getVendorId()
                    + "/" + boxId + "/ordersheets";
            try {
                r.put("response", objectMapper.readTree(coupangApiClient.get(path, "", account)));
            } catch (Exception e) {
                r.put("error", e.getMessage());
            }
            results.add(r);
        }
        return results;
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
