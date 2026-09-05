package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimAction;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.service.CarrierCodeService;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 쿠팡 클레임 처리 액션 어댑터 — 반품 3액션(FEATURE_2609_21 / 02).
 *
 * <p>쿠팡 상태 코드를 아는 <b>유일한 자리</b>다(D2). 교환 4액션은 seam 에만 선언돼 있고 여기서는
 * {@link UnsupportedOperationException} 을 던진다(05 에서 구현).
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 없음 — 외부 HTTP 다. DB 는 아예 건드리지 않는다(D7:
 * 로컬 상태는 다음 동기화가 갱신한다).
 * <p>⚠️ 이 어댑터는 조회를 하지 않는다 — 형제 라인·성공 기록은 서비스가 넘겨준다(D14).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangClaimActionAdapter implements ClaimActionAdapter {

    private static final String PLATFORM_COUPANG = "COUPANG";

    /** 회수 송장 등록은 반품·교환 공용 엔드포인트라 이 값으로 갈린다. */
    private static final String DELIVERY_TYPE_RETURN = "RETURN";

    /**
     * {@code platform_status} 원문 → 가능 액션 (D2·D3 화이트리스트).
     *
     * <p>여기 없는 값은 전부 <b>액션 없음</b>이다 — {@code RELEASE_STOP_UNCHECKED}(출고중지요청)·
     * {@code REQUEST_COUPANG_CHECK}(쿠팡확인요청)·{@code RETURNS_COMPLETED}(반품완료)뿐 아니라
     * <b>단축 코드({@code UC} 등)와 미지의 값도</b> 포함된다. 단축 코드는 01 이 하위호환으로 읽기만
     * 남긴 값이라 언제 저장됐는지 알 수 없다 — 낡았을 수 있는 상태에 되돌릴 수 없는 쓰기를 열지 않는다.
     */
    private static final Map<String, List<ClaimAction>> RETURN_ACTIONS_BY_STATUS = Map.of(
            "RETURNS_UNCHECKED", List.of(
                    ClaimAction.RETURN_RECEIVE_CONFIRM, ClaimAction.RETURN_COLLECT_INVOICE),
            "VENDOR_WAREHOUSE_CONFIRM", List.of(
                    ClaimAction.RETURN_APPROVE));

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final CarrierCodeService carrierCodeService;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return PLATFORM_COUPANG;
    }

    @Override
    public List<ClaimActionOption> availableActions(OrderClaim claim, Set<ClaimAction> alreadySucceeded) {
        if (claim.getClaimType() != ClaimType.RETURN) {
            return List.of();                       // 교환은 05 에서 구현한다
        }
        String status = claim.getPlatformStatus();
        if (status == null || status.isBlank()) {
            return List.of();                       // D3 — 모르면 열지 않는다
        }
        return RETURN_ACTIONS_BY_STATUS
                .getOrDefault(status.trim().toUpperCase(Locale.ROOT), List.of())
                .stream()
                .filter(action -> !alreadySucceeded.contains(action))
                .map(this::toOption)
                .toList();
    }

    @Override
    public ClaimActionOutcome execute(MarketplaceAccount account,
                                      List<OrderClaim> siblings,
                                      ClaimActionCommand command) {
        OrderClaim anchor = siblings.get(0);
        long receiptId = receiptId(anchor);         // 문자열로 보내면 쿠팡 400 — Number 로 싣는다

        return switch (command.action()) {
            case RETURN_RECEIVE_CONFIRM -> patch(
                    path(coupangProperties.getReturnReceiveConfirmPath(), account, receiptId),
                    receiveConfirmBody(account, receiptId), account);
            case RETURN_APPROVE -> patch(
                    path(coupangProperties.getReturnApprovalPath(), account, receiptId),
                    approvalBody(account, receiptId, siblings), account);
            case RETURN_COLLECT_INVOICE -> post(
                    path(coupangProperties.getReturnExchangeInvoicePath(), account, receiptId),
                    collectInvoiceBody(account, receiptId, command), account);
            default -> throw new UnsupportedOperationException(
                    "쿠팡 어댑터가 아직 지원하지 않는 액션입니다: " + command.action());
        };
    }

    private ClaimActionOption toOption(ClaimAction action) {
        // 반품 3액션은 값 선택이 없으므로 choices 는 빈 목록이다(교환 거부 사유가 05 에서 채운다).
        return new ClaimActionOption(action, action.getLabel(), action.getRequires(),
                List.of(), action.isIrreversible());
    }

    /** {@code {vendorId}}·{@code {receiptId}} 치환 — 기존 경로 조립 관례(replace)를 따른다. */
    private String path(String template, MarketplaceAccount account, long receiptId) {
        return template
                .replace("{vendorId}", account.getVendorId())
                .replace("{receiptId}", String.valueOf(receiptId));
    }

    private long receiptId(OrderClaim claim) {
        try {
            return Long.parseLong(claim.getExternalClaimId().trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "접수번호가 숫자가 아닙니다: " + claim.getExternalClaimId());
        }
    }

    private Map<String, Object> receiveConfirmBody(MarketplaceAccount account, long receiptId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendorId", account.getVendorId());
        body.put("receiptId", receiptId);
        return body;
    }

    private Map<String, Object> approvalBody(MarketplaceAccount account, long receiptId,
                                             List<OrderClaim> siblings) {
        Map<String, Object> body = receiveConfirmBody(account, receiptId);
        // 🔴 접수 단위 파라미터 — 클릭한 라인의 수량만 보내면 부분 승인이 되거나 400 이 난다(D6).
        body.put("cancelCount", siblings.stream()
                .mapToInt(claim -> claim.getQuantity() == null ? 0 : claim.getQuantity())
                .sum());
        return body;
    }

    private Map<String, Object> collectInvoiceBody(MarketplaceAccount account, long receiptId,
                                                   ClaimActionCommand command) {
        // 미등록 택배사면 여기서 400 — 전송 전에 끊는다(D16: 코드 원천은 CarrierCodeService 하나다).
        String deliveryCompanyCode = carrierCodeService.validateDeliveryCompanyCode(
                command.deliveryCompanyCode(), account.getPlatform());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("returnExchangeDeliveryType", DELIVERY_TYPE_RETURN);
        body.put("receiptId", receiptId);
        body.put("deliveryCompanyCode", deliveryCompanyCode);
        body.put("invoiceNumber", command.invoiceNumber().trim());
        if (command.regNumber() != null && !command.regNumber().isBlank()) {
            body.put("regNumber", command.regNumber().trim());
        }
        return body;
    }

    private ClaimActionOutcome patch(String path, Map<String, Object> body, MarketplaceAccount account) {
        return parse(coupangApiClient.patch(path, json(body), account));
    }

    private ClaimActionOutcome post(String path, Map<String, Object> body, MarketplaceAccount account) {
        return parse(coupangApiClient.post(path, json(body), account));
    }

    private String json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("클레임 액션 요청 직렬화 실패", e);
        }
    }

    /**
     * HTTP 200 이어도 바디 {@code code} 가 200 이 아닐 수 있다 → <b>바디를 읽고 판정</b>하고
     * {@code code}/{@code message} 원문을 그대로 담는다(D15).
     */
    private ClaimActionOutcome parse(String response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception e) {
            // 파싱 불가 = 성공으로 볼 근거가 없다. 원문을 잘라 감사기록에 남긴다.
            log.warn("클레임 액션 응답 파싱 실패: {}", response);
            return new ClaimActionOutcome(false, "PARSE_ERROR", truncate(response));
        }
        String code = root.path("code").asText("");
        String message = root.path("message").asText("");
        boolean succeeded = "200".equals(code) || "SUCCESS".equalsIgnoreCase(code);
        return new ClaimActionOutcome(succeeded, code, message);
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
