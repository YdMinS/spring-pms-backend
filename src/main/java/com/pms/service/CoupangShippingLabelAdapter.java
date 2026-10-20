package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderShipment;
import com.pms.domain.Platform;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.coupang.OrderUpserter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Coupang label-sheet rows (FEATURE_2610_23 / D21) — ordersheets (INSTRUCT) → flatten rows, moved from
 * {@link ShippingLabelServiceImpl} unchanged (D3). Coupang fills the internal-stage slot (D23 · D24).
 *
 * The query follows the {@code CoupangOrderSyncServiceImpl} pattern (status=INSTRUCT fixed).
 * 🔴 {@code INSTRUCT} here is a <b>Coupang query parameter</b>, not our status — the DB is not queried by status, so
 * it is not turned into {@code OrderStatus} (FEATURE_2609_26 / 04 §3-2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangShippingLabelAdapter implements ShippingLabelAdapter {

    private static final int MAX_PER_PAGE = 50;
    private static final int MAX_PAGES = 100;                    // 무한루프 가드
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String KST_OFFSET = "%2B09:00";        // +09:00, URL-encoded (+ → %2B)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");  // 쿠팡 createdAt 창은 KST 달력 기준

    /** 내부 시트 조회 창 폭 — to − from ≤ 30일(= 31일 창). instruct-days 30 이 실측 통과한 폭과 같다(D26). */
    private static final int MAX_WINDOW_DAYS = 30;

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;
    private final OrderUpserter orderUpserter;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    /** 단일 쿠팡 계정의 INSTRUCT ordersheets 를 페이징 조회하며 행으로 펼친다. */
    @Override
    public List<ShippingLabelRow> collectRows(MarketplaceAccount account) {
        String path = coupangProperties.getOrdersheetsPath()
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId());
        String baseQuery = baseQuery();

        List<ShippingLabelRow> rows = new ArrayList<>();
        String nextToken = null;
        int pages = 0;

        do {
            String query = (nextToken == null || nextToken.isBlank())
                    ? baseQuery
                    : baseQuery + "&nextToken=" + nextToken;

            JsonNode parsed = readTree(coupangApiClient.get(path, query, account));
            pages++;

            for (JsonNode box : parsed.path("data")) {
                flattenBox(account, box, rows);
            }
            // 시트에 실린 주문은 정의상 DB 에 있어야 한다 — 발송처리 매칭이 폴백에 기대지 않게 한다(PLAN 2609_13 D1).
            // ⚠️ 다운로드가 우선이다(D6). 저장 실패가 시트를 막으면 안 되므로 페이지 단위로 삼킨다(D4·D5).
            try {
                orderUpserter.upsertBoxes(account, parsed.path("data"));
            } catch (Exception e) {
                log.warn("송장시트 주문 적재 실패(시트는 계속 생성): account={} page={}", account.getId(), pages, e);
            }

            String prev = nextToken;
            nextToken = parsed.path("nextToken").asText("");
            // 무한루프 가드: 토큰 정체 또는 최대 페이지 초과 시 중단.
            if (nextToken.equals(prev) || pages >= MAX_PAGES) {
                break;
            }
        } while (!nextToken.isBlank());

        return rows;
    }

    @Override
    public List<ShippingLabelRow> collectOrderRows(MarketplaceAccount account, String externalOrderId,
                                                   Long orderItemId) {
        String path = coupangProperties.getOrdersheetByOrderPath()
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId())
                .replace("{orderId}", externalOrderId);

        List<ShippingLabelRow> rows = new ArrayList<>();
        try {
            JsonNode parsed = readTree(coupangApiClient.get(path, "", account));
            // 단건 조회는 잘못된 orderId 에도 200 + 비정상 봉투가 올 수 있어 "0행"과 "실패"가 섞인다.
            // 목록 경로와 달리 여기서 봉투를 검사해 둘을 분리한다(0행 자체는 정상 — 전량 취소된 주문).
            JsonNode data = parsed.path("data");
            if (parsed.path("code").asInt(200) != 200 || !data.isArray()) {
                throw new IllegalStateException("쿠팡 발주서 단건 응답 이상: code=" + parsed.path("code").asText());
            }
            for (JsonNode box : data) {
                flattenBox(account, box, rows);          // 목록 경로와 동일한 펼침·취소 제외 규칙
            }
        } catch (Exception e) {
            // 목록 다운로드와 같은 정책: 조회 실패를 빈 시트로 감추지 않는다.
            log.warn("주문 단건 송장시트 조회 실패: orderItemId={} orderId={}",
                    orderItemId, externalOrderId, e);
            // 위 봉투 검사가 던진 IllegalStateException 도 이 catch 에 걸린다. 그대로 다시 감싸면
            // "code=..." 진단 메시지가 cause 로 묻히므로 재던진다.
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("쿠팡 발주서 단건 조회 실패", e);
        }
        return rows;
    }

    /**
     * 결제완료(ACCEPT) 목록을 31일 창으로 나눠 끝까지 조회하며 대상 배송 묶음만 행으로 펼친다(D26).
     *
     * @return 목록에서 찾은 대상 shipmentBoxId
     */
    @Override
    public Set<String> collectInternalRows(MarketplaceAccount account, List<OrderShipment> internal,
                                           List<ShippingLabelRow> rows) {
        Set<String> targetBoxIds = new HashSet<>();
        internal.forEach(s -> targetBoxIds.add(s.getExternalShipmentId()));
        LocalDate end = LocalDate.now(KST);
        LocalDate oldest = internal.stream()
                .map(s -> s.getOrder().getOrderedAt())
                .filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .min(Comparator.naturalOrder())
                .orElse(end);
        LocalDate from = oldest.minusDays(1);
        String path = coupangProperties.getOrdersheetsPath()
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId());

        Set<String> found = new HashSet<>();
        while (!from.isAfter(end)) {
            LocalDate to = from.plusDays(MAX_WINDOW_DAYS).isAfter(end) ? end : from.plusDays(MAX_WINDOW_DAYS);
            String baseQuery = "createdAtFrom=" + from.format(DATE) + KST_OFFSET
                    + "&createdAtTo=" + to.format(DATE) + KST_OFFSET
                    + "&status=ACCEPT&maxPerPage=" + MAX_PER_PAGE;
            String nextToken = null;
            int pages = 0;
            do {
                String query = (nextToken == null || nextToken.isBlank())
                        ? baseQuery
                        : baseQuery + "&nextToken=" + nextToken;
                JsonNode parsed = readTree(coupangApiClient.get(path, query, account));
                pages++;
                for (JsonNode box : parsed.path("data")) {
                    String boxId = box.path("shipmentBoxId").asText("");
                    if (targetBoxIds.contains(boxId) && found.add(boxId)) {
                        flattenBox(account, box, rows);
                    }
                }
                String prev = nextToken;
                nextToken = parsed.path("nextToken").asText("");
                if (nextToken.equals(prev) || pages >= MAX_PAGES) {
                    break;
                }
            } while (!nextToken.isBlank());
            from = to.plusDays(1);
        }
        return found;
    }

    /**
     * INSTRUCT 조회 기본 쿼리 (status 고정, nextToken 제외).
     * 날짜는 KST: "yyyy-MM-dd+09:00" (+ 는 %2B 로 인코딩, 서명/전송 동일 문자열 사용).
     */
    private String baseQuery() {
        LocalDate to = LocalDate.now(KST);
        LocalDate from = to.minusDays(coupangProperties.getInstructDays());
        return "createdAtFrom=" + from.format(DATE) + KST_OFFSET
                + "&createdAtTo=" + to.format(DATE) + KST_OFFSET
                + "&status=INSTRUCT&maxPerPage=" + MAX_PER_PAGE;
    }

    /** box(shipmentBox) 1개를 orderItems N개로 펼쳐 rows 에 누적. 전량 확정취소 라인만 제외. */
    private void flattenBox(MarketplaceAccount account, JsonNode box, List<ShippingLabelRow> rows) {
        JsonNode receiver = box.path("receiver");
        String receiverName = receiver.path("name").asText("");
        String receiverPhone = toDomesticPhone(receiver.path("safeNumber").asText(null));
        String postCode = receiver.path("postCode").asText("");
        String address = ShippingLabelAdapter.joinAddress(receiver.path("addr1").asText(null),
                receiver.path("addr2").asText(null));

        String orderId = box.path("orderId").asText("");
        String shipmentBoxId = box.path("shipmentBoxId").asText("");
        String deliveryMessage = box.path("parcelPrintMessage").asText("");  // nullable → ""
        String sellerName = account.getSeller().getSellerName();
        // 시트/미리보기 출력용 표시값 — 엑셀 셀·응답 DTO 로만 흘러간다.
        String platform = account.getPlatform().name();

        for (JsonNode item : box.path("orderItems")) {
            int shipping = item.path("shippingCount").asInt(0);
            int cancel = item.path("cancelCount").asInt(0);
            // 발송 대상 수량은 확정취소(cancelCount)만 뺀다.
            // holdCountForCancel(환불대기)은 아직 미확정이라 여기서 빼서 라인을 숨기면,
            // 취소요청만 걸린 상품준비중(INSTRUCT) 주문이 송장 접수시트에서 통째로 사라진다
            // (실서버 "상품준비중인데 조회 안 됨" 원인). 노출하고 판매자가 편집(V2)에서 판단한다.
            int quantity = shipping - cancel;
            if (quantity <= 0) {
                continue;                                       // 전량 확정취소만 미발송
            }
            String vendorItemId = item.path("vendorItemId").asText("");
            rows.add(new ShippingLabelRow(
                    receiverName, receiverPhone, postCode, address,
                    productName(item), quantity,
                    DEFAULT_PARCEL_QUANTITY, vendorItemId,
                    orderId, deliveryMessage, shipmentBoxId,
                    sellerName, platform));
        }
    }

    /**
     * 상품명 컬럼 값 결정.
     * 등록상품명(sellerProductName) 우선 — 판매자가 상품 등록 시 지은 이름을 그대로 노출한다.
     * 값이 비어 오면 노출옵션명(vendorItemName)으로 폴백해 빈 상품명을 방지한다.
     */
    private String productName(JsonNode item) {
        String sellerProductName = item.path("sellerProductName").asText("");
        return sellerProductName.isBlank() ? item.path("vendorItemName").asText("") : sellerProductName;
    }

    /**
     * 안심번호 E.164 → 국내형식. "+82" 로 시작하면 "0" 으로 치환(+821012345678 → 01012345678).
     * 그 외/빈 값은 그대로, null → "".
     */
    private String toDomesticPhone(String phone) {
        if (phone == null) {
            return "";
        }
        if (phone.startsWith("+82")) {
            return "0" + phone.substring(3);
        }
        return phone;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 ordersheets 응답 파싱 실패", e);
        }
    }
}
