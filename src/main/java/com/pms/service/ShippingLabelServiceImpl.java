package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ShippingLabelService} 구현 — 쿠팡 ordersheets(INSTRUCT) 조회 → 행 펼침 → xlsx.
 *
 * 쿼리 빌드는 {@code CoupangOrderSyncServiceImpl} 패턴을 따르되 status=INSTRUCT 고정, DB 미접근이다.
 * @Transactional 불필요(DB 미접근).
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

    private static final String[] HEADERS = {
            "받는사람 이름", "전화번호", "우편번호", "주소", "상품명", "수량",
            "내품수량", "주문번호", "배송메시지", "관리코드", "판매자", "플랫폼"
    };

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ObjectMapper objectMapper;

    @Override
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
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(coupangProperties.getInstructDays());
        return "createdAtFrom=" + from.format(DATE) + KST_OFFSET
                + "&createdAtTo=" + to.format(DATE) + KST_OFFSET
                + "&status=INSTRUCT&maxPerPage=" + MAX_PER_PAGE;
    }

    /** box(shipmentBox) 1개를 orderItems N개로 펼쳐 rows 에 누적. 발주가능수량 ≤ 0 라인은 제외. */
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
            int hold = item.path("holdCountForCancel").asInt(0);
            int quantity = shipping - (cancel + hold);         // 발주가능수량
            if (quantity <= 0) {
                continue;                                       // 취소분 미발송
            }
            rows.add(new ShippingLabelRow(
                    receiverName, receiverPhone, postCode, address,
                    item.path("vendorItemName").asText(""), quantity,
                    orderId, deliveryMessage, shipmentBoxId,
                    sellerName, platform));
        }
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

    @Override
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
                dataRow.createCell(5).setCellValue(row.quantity());          // 수량
                dataRow.createCell(6).setCellValue(row.quantity());          // 내품수량(라인 단위라 동일)
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
