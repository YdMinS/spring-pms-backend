package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.dto.request.ShippingLabelExportRequest.ExportRow;
import com.pms.dto.response.ShippingLabelPreviewRow;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ShippingLabelService} 구현 — 쿠팡 ordersheets(INSTRUCT) 조회 → 행 펼침 → xlsx.
 *
 * 쿼리 빌드는 {@code CoupangOrderSyncServiceImpl} 패턴을 따른다(status=INSTRUCT 고정).
 * 계정·seller 는 리포지토리에서 {@code @EntityGraph} 로 eager fetch 하므로, 외부 HTTP 루프를
 * 도는 이 서비스는 @Transactional 없이도 seller.sellerName 접근이 안전하다(open-in-view=false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingLabelServiceImpl implements ShippingLabelService {

    private static final String PLATFORM_COUPANG = "COUPANG";
    private static final int MAX_PER_PAGE = 50;
    private static final int MAX_PAGES = 100;                    // 무한루프 가드
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String KST_OFFSET = "%2B09:00";        // +09:00, URL-encoded (+ → %2B)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");  // 쿠팡 createdAt 창은 KST 달력 기준

    /** 택배수량(박스·라벨 수) 기본값 — 라인당 1박스. 추후 사용자 조정 가능하게 확장 예정. */
    private static final int DEFAULT_PARCEL_QUANTITY = 1;

    private static final String[] HEADERS = {
            "받는사람 이름", "전화번호", "우편번호", "주소", "상품명", "택배수량",
            "내품수량", "주문번호", "배송메시지", "관리코드", "판매자", "플랫폼"
    };

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ObjectMapper objectMapper;
    private final OrderItemRepository orderItemRepository;

    public List<ShippingLabelRow> collectRows(Long sellerId) {
        List<MarketplaceAccount> accounts = (sellerId == null)
                ? marketplaceAccountRepository.findByIsActiveTrue()
                : marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId);

        List<ShippingLabelRow> rows = new ArrayList<>();
        int targetAccounts = 0;
        int failedAccounts = 0;
        for (MarketplaceAccount account : accounts) {
            if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
                continue;
            }
            targetAccounts++;
            try {
                rows.addAll(collectAccountRows(account));
            } catch (Exception e) {
                // 한 계정 오류가 전체를 막지 않는다 — 로그 후 계속.
                failedAccounts++;
                log.warn("송장 접수시트 계정 조회 실패: account={} platform={}",
                        account.getId(), account.getPlatform(), e);
            }
        }
        // 대상 쿠팡 계정 전체가 실패해 한 행도 못 모았으면 빈 파일로 감추지 말고 오류를 드러낸다
        // (컨트롤러가 500 으로 매핑 — "정상인데 INSTRUCT 주문 0건" 과 "조회 실패" 를 구분).
        if (rows.isEmpty() && targetAccounts > 0 && failedAccounts == targetAccounts) {
            throw new IllegalStateException("쿠팡 ordersheets 조회 실패 — 대상 계정 전체 오류");
        }
        return rows;
    }

    @Override
    public List<ShippingLabelPreviewRow> previewRows(Long sellerId) {
        return collectRows(sellerId).stream().map(ShippingLabelPreviewRow::from).toList();
    }

    @Override
    public List<ShippingLabelPreviewRow> previewRowsByOrder(Long orderItemId) {
        OrderItem order = orderItemRepository.findWithAccountAndSellerById(orderItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderItemId));

        MarketplaceAccount account = order.getMarketplaceAccount();
        if (!PLATFORM_COUPANG.equals(account.getPlatform())) {
            throw new IllegalArgumentException("쿠팡 주문만 송장시트를 만들 수 있습니다: " + account.getPlatform());
        }

        String path = coupangProperties.getOrdersheetByOrderPath()
                .replace("{vendorId}", account.getVendorId())
                .replace("{orderId}", order.getExternalOrderId());

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
                    orderItemId, order.getExternalOrderId(), e);
            // 위 봉투 검사가 던진 IllegalStateException 도 이 catch 에 걸린다. 그대로 다시 감싸면
            // "code=..." 진단 메시지가 cause 로 묻히므로 재던진다.
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("쿠팡 발주서 단건 조회 실패", e);
        }
        return rows.stream().map(ShippingLabelPreviewRow::from).toList();
    }

    /**
     * 편집된 export rows 를 xlsx bytes 로 변환한다.
     *
     * <p>편집분 재매칭 없이 posted rows 그대로 {@link #toXlsx}로 넘긴다(비대칭 의도 — rowKey 는
     * xlsx 미출력이라 vendorItemId 는 ""로 채운다).
     */
    @Override
    public byte[] toXlsxFromExport(List<ExportRow> rows) {
        List<ShippingLabelRow> mapped = rows.stream()
                .map(e -> new ShippingLabelRow(
                        e.receiverName(), e.receiverPhone(), e.postCode(), e.address(),
                        e.productName(), e.quantity(),
                        e.parcelQuantity(), "",
                        e.orderId(), e.deliveryMessage(), e.shipmentBoxId(),
                        e.sellerName(), e.platform()))
                .toList();
        return toXlsx(mapped);
    }

    /** 단일 쿠팡 계정의 INSTRUCT ordersheets 를 페이징 조회하며 행으로 펼친다. */
    private List<ShippingLabelRow> collectAccountRows(MarketplaceAccount account) {
        String path = coupangProperties.getOrdersheetsPath().replace("{vendorId}", account.getVendorId());
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

            String prev = nextToken;
            nextToken = parsed.path("nextToken").asText("");
            // 무한루프 가드: 토큰 정체 또는 최대 페이지 초과 시 중단.
            if (nextToken.equals(prev) || pages >= MAX_PAGES) {
                break;
            }
        } while (!nextToken.isBlank());

        return rows;
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
        String address = joinAddress(receiver.path("addr1").asText(null), receiver.path("addr2").asText(null));

        String orderId = box.path("orderId").asText("");
        String shipmentBoxId = box.path("shipmentBoxId").asText("");
        String deliveryMessage = box.path("parcelPrintMessage").asText("");  // nullable → ""
        String sellerName = account.getSeller().getSellerName();
        String platform = account.getPlatform();

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

    /** addr1 + " " + addr2 결합. null 파트는 공백 처리 후 trim. */
    private String joinAddress(String addr1, String addr2) {
        String a1 = (addr1 == null) ? "" : addr1;
        String a2 = (addr2 == null) ? "" : addr2;
        return (a1 + " " + a2).trim();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 ordersheets 응답 파싱 실패", e);
        }
    }

    public byte[] toXlsx(List<ShippingLabelRow> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("송장접수");

            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                header.createCell(c).setCellValue(HEADERS[c]);
            }

            int r = 1;
            for (ShippingLabelRow row : rows) {
                Row dataRow = sheet.createRow(r++);
                dataRow.createCell(0).setCellValue(row.receiverName());
                dataRow.createCell(1).setCellValue(row.receiverPhone());
                dataRow.createCell(2).setCellValue(row.postCode());
                dataRow.createCell(3).setCellValue(row.address());
                dataRow.createCell(4).setCellValue(row.productName());
                // 택배수량(박스·라벨 수) — 기본 1이며 V2 편집 시 사용자 조정값이 반영된다.
                // 실제 주문 개수는 내품수량에만 노출(한 송장에 "N개" 표기).
                dataRow.createCell(5).setCellValue(row.parcelQuantity());     // 택배수량
                dataRow.createCell(6).setCellValue(row.quantity());          // 내품수량(주문 개수)
                dataRow.createCell(7).setCellValue(row.orderId());
                dataRow.createCell(8).setCellValue(row.deliveryMessage());
                dataRow.createCell(9).setCellValue(row.shipmentBoxId());     // 관리코드(업로드 레그 매칭 키)
                dataRow.createCell(10).setCellValue(row.sellerName());
                dataRow.createCell(11).setCellValue(row.platform());
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("송장 접수시트 xlsx 생성 실패", e);
        }
    }
}
