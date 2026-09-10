package com.pms.service.settlement.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementAdjustmentType;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementType;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.settlement.SettlementAdjustmentDraft;
import com.pms.service.settlement.SettlementLineDraft;
import com.pms.service.settlement.SettlementPayoutDraft;
import com.pms.service.settlement.SettlementSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 쿠팡 정산 조회 — 매출내역(revenue-history) + 지급내역(settlement-histories) (FEATURE_2609_30 / PLAN D5).
 *
 * <p>🔴 <b>두 피드는 게이트웨이 경로도 응답 모양도 다르다</b>: 매출내역은 {@code openapi} + {@code {data:[...]}}
 * + 페이징, 지급내역은 {@code marketplace_openapi} + <b>최상위 배열</b> + 페이징 없음. 파서를 공유하지 말 것.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 외부 HTTP 루프다. 저장은 전부 중립
 * 서비스가 한다({@code CoupangInquiryAdapter} 와 같은 자세).
 *
 * <p>🔴 <b>파서는 방어적이다.</b> 쿠팡 문서가 틀린 전례가 있어({@code reference_coupang_shipping_place_lookup_api})
 * ① envelope 은 {@code {"data":[...]}} 와 <b>최상위 배열</b>을 모두 받고 ② 금액·수량 키는 후보를 순서대로
 * 훑으며 ③ 모르는 값은 예외 대신 null 로 떨어뜨린다. 다만 유일키 4개가 비면 그 라인은 멱등 upsert 가
 * 불가능하므로 {@link SettlementLineDraft} 가 {@code IllegalArgumentException} 을 던진다.
 *
 * <p>⚠️ 빈 구간은 <b>HTTP 200 + 빈 배열</b>이다(에러 아님) — 판매가 없는 날에도 스케줄이 조용히 지나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangSettlementSource implements SettlementSource {

    /** 페이징 무한루프 가드. {@code nextToken} 을 못 읽는 응답이 와도 여기서 멈춘다. */
    static final int MAX_PAGES = 200;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 매출/환불 판정: 이 조각이 들어 있으면 환불이다. 모르는 값은 SALE 로 흡수한다. */
    private static final List<String> REFUND_TOKENS = List.of("REFUND", "RETURN", "CANCEL", "환불", "반품", "취소");

    /**
     * 지급내역 응답에서 우리가 <b>의미를 아는</b> 필드. 여기 없는 금액성 필드는 조정 {@code OTHER} 로
     * 흘려보내고 필드명을 note 에 남긴다 — 모르는 돈을 조용히 버리면 검증식이 영영 맞지 않는다(D8·D13).
     *
     * <p>🔴 <b>실계정이 주는 이름을 빠짐없이 적어야 한다.</b> 빠지면 이미 매핑한 금액이 이름만 다른 채로
     * {@code OTHER} 조정 한 행에 다시 담겨, 정산 상세에 정체불명 금액이 뜬다. prod 실측(2026-09-10):
     * {@code settlementTargetAmount}·{@code settlementAmount}·{@code sellerServiceFee} 3개가 빠져 있어
     * 주/월 정산 23건 전건에 690만원대 OTHER 행이 붙었다. 셋은 이미 읽고 있는 값의 별칭이다 —
     * 검산: 6,903,562(OTHER 합) − 3,369,281(finalAmount) − 444,789(serviceFee) = 3,089,492(정산대상액).
     */
    private static final Set<String> KNOWN_PAYOUT_FIELDS = Set.of(
            "settlementType", "settlementDate", "finalSettlementDate", "revenueRecognitionYearMonth",
            "revenueRecognitionDateFrom", "revenueRecognitionDateTo", "totalSale", "totalSaleAmount",
            "serviceFee", "serviceFeeAmount", "finalAmount", "status", "deductionAmount",
            "debtOfLastWeek", "pendingReleasedAmount", "vendorId", "settlementYearMonth",
            "settlementTargetAmount", "settlementAmount", "sellerServiceFee");

    /** 금액성 필드 판정(이름 기준). 날짜·식별자를 금액으로 오해하지 않게 접미사로 좁힌다. */
    private static final List<String> AMOUNT_SUFFIXES = List.of("Amount", "amount", "Fee", "fee", "Sale", "sale");

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    @Override
    public void fetchRevenue(MarketplaceAccount account, LocalDate from, LocalDate to,
                             Consumer<List<SettlementLineDraft>> pageConsumer) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("조회 기간(from, to)이 올바르지 않습니다.");
        }
        String vendorId = CoupangCredentials.of(account).getVendorId();
        // 쿠팡 상한(31일)을 넘는 요청은 잘라서 여러 번 호출한다 — 400 을 사용자에게 그대로 보여주지 않는다.
        for (Window window : split(from, to)) {
            fetchWindow(account, vendorId, window, pageConsumer);
        }
    }

    /** [from, to] 를 {@code revenueWindowMaxDays} 일(양끝 포함) 이하 구간으로 자른다. */
    List<Window> split(LocalDate from, LocalDate to) {
        int maxDays = Math.max(1, coupangProperties.getRevenueWindowMaxDays());
        List<Window> windows = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate end = cursor.plusDays(maxDays - 1L);
            if (end.isAfter(to)) {
                end = to;
            }
            windows.add(new Window(cursor, end));
            cursor = end.plusDays(1);
        }
        return windows;
    }

    /**
     * 한 구간을 페이징하며 읽는다. 페이지 하나를 읽을 때마다 콜백한다.
     *
     * <p>페이징 규약은 {@code ShippingLabelServiceImpl} 의 nextToken 루프를 그대로 따른다 —
     * <b>토큰이 직전과 같으면 중단</b>(무한루프 가드) + {@link #MAX_PAGES} 상한.
     */
    private void fetchWindow(MarketplaceAccount account, String vendorId, Window window,
                             Consumer<List<SettlementLineDraft>> pageConsumer) {
        String path = coupangProperties.getRevenueHistoryPath();
        String baseQuery = "vendorId=" + vendorId
                + "&recognitionDateFrom=" + window.from().format(DATE)
                + "&recognitionDateTo=" + window.to().format(DATE)
                + "&maxPerPage=" + coupangProperties.getRevenueMaxPerPage();

        // ⚠️ 첫 페이지의 token 은 생략이 아니라 빈 문자열이다.
        String token = "";
        int pages = 0;
        while (true) {
            JsonNode parsed = readTree(coupangApiClient.get(path, baseQuery + "&token=" + token, account));
            pages++;

            List<SettlementLineDraft> page = parseLines(parsed);
            if (!page.isEmpty()) {
                pageConsumer.accept(page);
            }

            String prev = token;
            token = nextToken(parsed);
            if (token.isBlank() || token.equals(prev) || pages >= MAX_PAGES) {
                if (pages >= MAX_PAGES) {
                    log.warn("정산 매출내역 페이지 상한 도달 — 이후 페이지 미조회: account={} window={}~{}",
                            account.getId(), window.from(), window.to());
                }
                break;
            }
        }
    }

    /**
     * 지급내역(settlement-histories) 조회 — 인식월 1개, <b>페이징 없음</b> (FEATURE_2609_30 / 02).
     *
     * <p>🔴 매출내역과 <b>게이트웨이 경로가 다르고</b>({@code marketplace_openapi}) <b>응답 최상위가 배열</b>이라
     * {@link #parseLines} 파서를 재사용하지 않는다. 같은 파서를 쓰면 {@code data} 를 찾다가 0건으로 조용히
     * 지나간다.
     *
     * <p>⚠️ 당월을 넘는 월은 쿠팡이 400 을 준다 — 호출자가 미래 월을 넘기지 않는다.
     */
    @Override
    public List<SettlementPayoutDraft> fetchPayouts(MarketplaceAccount account, YearMonth month) {
        if (month == null) {
            throw new IllegalArgumentException("매출인식월(month)을 지정해야 합니다.");
        }
        // vendorId 는 지급 묶음의 소유 축(계정)이라 함께 보낸다 — 계정별 응답을 받기 위한 것이지
        // 페이징 파라미터가 아니다.
        String query = "vendorId=" + CoupangCredentials.of(account).getVendorId()
                + "&revenueRecognitionYearMonth=" + month;
        return parsePayouts(readTree(coupangApiClient.get(
                coupangProperties.getSettlementHistoriesPath(), query, account)));
    }

    /**
     * 지급 묶음 배열 파싱. 🔴 <b>원소를 합치지 않는다</b> — 같은 인식월에 주정산·월정산·추가정산·유보금이
     * 함께 오고, 합치면 실제 입금 건수와 화면 건수가 달라진다(D5-3).
     *
     * <p>최상위 배열이 정상이며, 방어적으로 {@code data} 래핑도 받는다(쿠팡 문서가 틀린 전례).
     */
    List<SettlementPayoutDraft> parsePayouts(JsonNode root) {
        JsonNode array = root.isArray() ? root : root.path("data");
        if (!array.isArray()) {
            return List.of();
        }
        List<SettlementPayoutDraft> drafts = new ArrayList<>();
        for (JsonNode node : array) {
            drafts.add(toPayoutDraft(node));
        }
        return drafts;
    }

    private SettlementPayoutDraft toPayoutDraft(JsonNode node) {
        return new SettlementPayoutDraft(
                SettlementType.from(text(node, "settlementType")),
                text(node, "revenueRecognitionYearMonth", "settlementYearMonth"),
                date(node, "revenueRecognitionDateFrom"),
                date(node, "revenueRecognitionDateTo"),
                date(node, "settlementDate"),
                date(node, "finalSettlementDate"),
                decimal(node, "totalSale", "totalSaleAmount"),
                decimal(node, "serviceFee", "serviceFeeAmount"),
                decimal(node, "finalAmount"),
                payoutStatus(text(node, "status")),
                adjustments(node));
    }

    /**
     * 배치 레벨 금액 → 조정 draft (D8).
     *
     * <p>🔴 값은 전부 양수로 오고 부호는 타입이 결정한다 — 여기서 −1 을 곱하지 않는다.
     * 🔴 {@code PENDING_RELEASE} 는 "보류 해제 후 <b>앞으로</b> 정산에 포함될 금액"이라 이번 지급액이
     * 아니다. 행으로는 남기되 합산에서 뺀다({@link com.pms.service.settlement.SettlementReconciler}).
     */
    private List<SettlementAdjustmentDraft> adjustments(JsonNode node) {
        List<SettlementAdjustmentDraft> adjustments = new ArrayList<>();
        addAdjustment(adjustments, SettlementAdjustmentType.DEDUCTION, decimal(node, "deductionAmount"), null);
        addAdjustment(adjustments, SettlementAdjustmentType.DEBT_CARRIED, decimal(node, "debtOfLastWeek"), null);
        addAdjustment(adjustments, SettlementAdjustmentType.PENDING_RELEASE,
                decimal(node, "pendingReleasedAmount"), "정보성 — 이번 지급액이 아닙니다");

        // 우리가 매핑하지 않은 금액 필드는 버리지 않고 OTHER 한 행으로 모은다(필드명은 note 에).
        BigDecimal other = null;
        List<String> names = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String name = field.getKey();
            if (KNOWN_PAYOUT_FIELDS.contains(name) || !looksLikeAmount(name)) {
                continue;
            }
            BigDecimal value = decimal(node, name);
            if (value == null || value.signum() == 0) {
                continue;
            }
            other = sum(other, value);
            names.add(name);
        }
        if (other != null) {
            adjustments.add(new SettlementAdjustmentDraft(SettlementAdjustmentType.OTHER, other,
                    "미매핑 필드: " + String.join(", ", names)));
        }
        return adjustments;
    }

    private static void addAdjustment(List<SettlementAdjustmentDraft> target, SettlementAdjustmentType type,
                                      BigDecimal amount, String note) {
        if (amount != null && amount.signum() != 0) {
            target.add(new SettlementAdjustmentDraft(type, amount, note));
        }
    }

    private static boolean looksLikeAmount(String fieldName) {
        return AMOUNT_SUFFIXES.stream().anyMatch(fieldName::endsWith);
    }

    /** {@code DONE → PAID}, {@code SUBJECT → SCHEDULED}. 모르는 값은 UNKNOWN 으로 흡수한다(돈은 이미 움직였다). */
    static SettlementPayoutStatus payoutStatus(String raw) {
        if (raw == null) {
            return SettlementPayoutStatus.UNKNOWN;
        }
        return switch (raw.trim().toUpperCase()) {
            case "DONE" -> SettlementPayoutStatus.PAID;
            case "SUBJECT" -> SettlementPayoutStatus.SCHEDULED;
            default -> SettlementPayoutStatus.UNKNOWN;
        };
    }

    /** 라인 배열 위치 — {@code data} 래핑과 최상위 배열을 모두 받는다. */
    List<SettlementLineDraft> parseLines(JsonNode root) {
        JsonNode array = root.isArray() ? root : root.path("data");
        if (!array.isArray()) {
            return List.of();
        }
        List<SettlementLineDraft> drafts = new ArrayList<>();
        for (JsonNode line : array) {
            drafts.add(toDraft(line));
        }
        return drafts;
    }

    private String nextToken(JsonNode root) {
        if (root.isArray()) {
            return "";                                  // 최상위 배열 응답에는 페이징 정보가 없다
        }
        if (root.has("hasNext") && !root.path("hasNext").asBoolean(false)) {
            return "";
        }
        return root.path("nextToken").asText("");
    }

    private SettlementLineDraft toDraft(JsonNode line) {
        return new SettlementLineDraft(
                text(line, "orderId", "externalOrderId"),
                text(line, "vendorItemId", "platformOptionId"),
                saleType(text(line, "saleType", "settlementType", "type")),
                date(line, "recognitionDate", "saleRecognitionDate"),
                date(line, "saleDate", "salesDate"),
                date(line, "settlementDate"),
                date(line, "finalSettlementDate"),
                integer(line, "quantity", "saleCount", "shippingCount"),
                decimal(line, "saleAmount", "salePrice", "totalSalePrice"),
                decimal(line, "serviceFee"),
                decimal(line, "serviceFeeVat"),
                decimal(line, "serviceFeeRatio"),
                // 셀러 부담 쿠폰만 더한다 — coupangDiscountCoupon(플랫폼 부담)은 우리 비용이 아니다.
                sum(decimal(line, "sellerDiscountCoupon"), decimal(line, "downloadableCoupon")),
                sum(decimal(line, "deliveryFee", "deliveryFeeAmount"),
                        decimal(line, "remoteDeliveryFee", "remoteAreaDeliveryFee")),
                decimal(line, "settlementAmount"),
                line);
    }

    /**
     * 매출/환불 판정. 모르는 값은 {@link SaleType#SALE} 로 흡수한다 — 예외를 던지면 값 하나가 회차 전체를
     * 깨고, 환불을 매출로 세는 편이 라인을 통째로 잃는 것보다 낫다(대사에서 드러난다).
     */
    static SaleType saleType(String raw) {
        if (raw == null) {
            return SaleType.SALE;
        }
        String upper = raw.trim().toUpperCase();
        return REFUND_TOKENS.stream().anyMatch(upper::contains) ? SaleType.REFUND : SaleType.SALE;
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                String raw = value.asText("");
                if (!raw.isBlank()) {
                    return raw.trim();
                }
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... keys) {
        String raw = text(node, keys);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;                                // 모르는 형식은 null — 회차를 깨지 않는다
        }
    }

    private static Integer integer(JsonNode node, String... keys) {
        BigDecimal value = decimal(node, keys);
        return value == null ? null : value.intValue();
    }

    private static LocalDate date(JsonNode node, String... keys) {
        String raw = text(node, keys);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw, DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** null 은 0 으로 보지 않는다 — 둘 다 null 이면 결과도 null(모르는 것은 모르는 채로). */
    private static BigDecimal sum(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : left.add(right);
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalArgumentException("정산 매출내역 응답을 해석하지 못했습니다.", e);
        }
    }

    /** 31일 이하로 잘린 조회 구간(양끝 포함). */
    record Window(LocalDate from, LocalDate to) {
    }
}
