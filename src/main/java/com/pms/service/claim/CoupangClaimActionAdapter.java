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
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 쿠팡 클레임 처리 액션 어댑터 — 반품 3액션(02) + 교환 4액션(05).
 *
 * <p>쿠팡 상태 코드를 아는 <b>유일한 자리</b>다(D2). 반품은 {@code platform_status} 1축,
 * 교환은 {@code platform_status × collect_status} 2축으로 판정한다.
 *
 * <p>🔴 X3(재발송 송장)의 {@code shipmentBoxId} 는 <b>입고확인 후 새로 생성된 재배송 박스</b>이고
 * 우리가 저장한 {@code external_box_id}(원 배송번호)와 다른 값이다(D12). 전송 직전 재조회로 얻으며,
 * 못 찾으면 <b>전송하지 않고 400</b> — 원 배송번호로 폴백하면 200 이 돌아오고 엉뚱한 박스에 송장이 붙는다.
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
    private static final String DELIVERY_TYPE_EXCHANGE = "EXCHANGE";

    /**
     * 교환 거부 사유 — <b>2개뿐</b>이며 이 목록이 유일한 소유자다(D19).
     *
     * <p>{@code availableActions} 의 {@code choices} 로 그대로 내려가고 서버 검증도 같은 목록을 쓴다 —
     * 클라이언트에 코드→라벨 상수를 만들게 두면 화면에 보이는 선택지와 서버가 받는 값이 갈린다.
     */
    private static final List<ActionChoice> EXCHANGE_REJECT_CHOICES = List.of(
            new ActionChoice("SOLDOUT", "교환할 상품이 품절"),
            new ActionChoice("WITHDRAW", "고객이 교환요청을 철회함"));

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

    /**
     * 교환의 {@code platform_status} 원문 → 회수상태와 무관하게 열리는 액션.
     *
     * <p>{@code SUCCESS}·{@code REJECT}·{@code CANCEL} 과 미지의 값은 여기 없다 = 액션 없음(D3).
     */
    private static final Map<String, List<ClaimAction>> EXCHANGE_ACTIONS_BY_STATUS = Map.of(
            "RECEIPT", List.of(ClaimAction.EXCHANGE_REJECT),
            "PROGRESS", List.of(ClaimAction.EXCHANGE_RECEIVE_CONFIRM));

    /**
     * {@code platform_status|collect_status} → 회수상태가 맞아야만 열리는 액션.
     *
     * <p>🔴 두 축을 <b>쌍으로</b> 본다. {@code collect_status} 만으로 판정하면
     * {@code RECEIPT + CompleteCollect} 같은 조합에서 재발송 송장이 열린다 —
     * 값이 없으면(null) 어느 쌍에도 걸리지 않으므로 액션도 열리지 않는다(D3).
     */
    private static final Map<String, List<ClaimAction>> EXCHANGE_ACTIONS_BY_COLLECT_STATUS = Map.of(
            "RECEIPT|BEFOREDIRECTION", List.of(ClaimAction.EXCHANGE_COLLECT_INVOICE),
            "PROGRESS|COMPLETECOLLECT", List.of(ClaimAction.EXCHANGE_RESHIP_INVOICE));

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final CarrierCodeService carrierCodeService;
    /** X3 의 재배송 박스 재조회 전용 — 조회 관례(서명·페이징)를 복사하지 않으려고 조회 어댑터를 빌린다. */
    private final CoupangClaimAdapter coupangClaimAdapter;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return PLATFORM_COUPANG;
    }

    @Override
    public List<ClaimActionOption> availableActions(OrderClaim claim, Set<ClaimAction> alreadySucceeded) {
        String status = normalize(claim.getPlatformStatus());
        if (status == null) {
            return List.of();                       // D3 — 모르면 열지 않는다
        }
        List<ClaimAction> candidates = (claim.getClaimType() == ClaimType.EXCHANGE)
                ? exchangeCandidates(status, normalize(claim.getCollectStatus()))
                : RETURN_ACTIONS_BY_STATUS.getOrDefault(status, List.of());

        return candidates.stream()
                .filter(action -> !alreadySucceeded.contains(action))
                .map(this::toOption)
                .toList();
    }

    /**
     * 교환 판정 = {@code platform_status} × {@code collect_status} 2축.
     *
     * <p>회수상태를 요구하지 않는 액션(입고확인·거부)에 회수상태가 맞을 때만 열리는 액션
     * (회수송장·재발송송장)을 더한다. 회수상태가 없으면 앞쪽만 남는다.
     */
    private List<ClaimAction> exchangeCandidates(String platformStatus, String collectStatus) {
        List<ClaimAction> base = EXCHANGE_ACTIONS_BY_STATUS.getOrDefault(platformStatus, List.of());
        if (collectStatus == null) {
            return base;
        }
        List<ClaimAction> gated = EXCHANGE_ACTIONS_BY_COLLECT_STATUS
                .getOrDefault(platformStatus + "|" + collectStatus, List.of());
        if (gated.isEmpty()) {
            return base;
        }
        List<ClaimAction> all = new ArrayList<>(base);
        all.addAll(gated);
        return all;
    }

    /** 화이트리스트 조회용 정규화 — 값이 없으면 null(= 조회하지 않는다). */
    private String normalize(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw.trim().toUpperCase(Locale.ROOT);
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
                    collectInvoiceBody(account, receiptId, command, DELIVERY_TYPE_RETURN), account);

            case EXCHANGE_RECEIVE_CONFIRM -> patch(
                    path(coupangProperties.getExchangeReceiveConfirmPath(), account, receiptId),
                    exchangeBody(account, receiptId), account);
            case EXCHANGE_REJECT -> patch(
                    path(coupangProperties.getExchangeRejectionPath(), account, receiptId),
                    rejectionBody(account, receiptId, command), account);
            // X4 는 반품 R3 과 같은 엔드포인트다 — returnExchangeDeliveryType 만 다르다.
            case EXCHANGE_COLLECT_INVOICE -> post(
                    path(coupangProperties.getReturnExchangeInvoicePath(), account, receiptId),
                    collectInvoiceBody(account, receiptId, command, DELIVERY_TYPE_EXCHANGE), account);
            case EXCHANGE_RESHIP_INVOICE -> post(
                    path(coupangProperties.getExchangeInvoicePath(), account, receiptId),
                    reshipInvoiceBody(account, anchor, receiptId, command), account);

            default -> throw new UnsupportedOperationException(
                    "쿠팡 어댑터가 아직 지원하지 않는 액션입니다: " + command.action());
        };
    }

    private ClaimActionOption toOption(ClaimAction action) {
        // 값 선택이 필요한 액션은 교환 거부 하나다 — 나머지는 빈 목록이라 UI 가 선택지를 그리지 않는다.
        List<ActionChoice> choices = (action == ClaimAction.EXCHANGE_REJECT)
                ? EXCHANGE_REJECT_CHOICES
                : List.of();
        return new ClaimActionOption(action, action.getLabel(), action.getRequires(),
                choices, action.isIrreversible());
    }

    /**
     * {@code {vendorId}}·{@code {receiptId}}·{@code {exchangeId}} 치환 — 기존 경로 조립 관례(replace).
     *
     * <p>반품은 {@code receiptId}, 교환은 {@code exchangeId} 로 자리표시자 이름만 다르고 값은 같은
     * {@code external_claim_id} 다 — 그래서 한 메서드가 둘 다 채운다.
     */
    private String path(String template, MarketplaceAccount account, long claimId) {
        return template
                .replace("{vendorId}", account.getVendorId())
                .replace("{receiptId}", String.valueOf(claimId))
                .replace("{exchangeId}", String.valueOf(claimId));
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

    /**
     * 회수 송장 바디 — 반품(R3)·교환(X4) <b>공용</b>이다.
     *
     * <p>복사해서 두 벌로 만들면 택배사 해석·에러 매핑이 갈린다. 다른 것은
     * {@code returnExchangeDeliveryType} 하나뿐이고, {@code receiptId} 자리에는 교환도
     * {@code exchangeId} 를 넣는다(문서상 필드명이 같다).
     */
    private Map<String, Object> collectInvoiceBody(MarketplaceAccount account, long receiptId,
                                                   ClaimActionCommand command, String deliveryType) {
        // 미등록 택배사면 여기서 400 — 전송 전에 끊는다(D16: 코드 원천은 CarrierCodeService 하나다).
        String deliveryCompanyCode = carrierCodeService.validateDeliveryCompanyCode(
                command.deliveryCompanyCode(), account.getPlatform());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("returnExchangeDeliveryType", deliveryType);
        body.put("receiptId", receiptId);
        body.put("deliveryCompanyCode", deliveryCompanyCode);
        body.put("invoiceNumber", command.invoiceNumber().trim());
        if (command.regNumber() != null && !command.regNumber().isBlank()) {
            body.put("regNumber", command.regNumber().trim());
        }
        return body;
    }

    private Map<String, Object> exchangeBody(MarketplaceAccount account, long exchangeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendorId", account.getVendorId());
        body.put("exchangeId", exchangeId);         // 문자열로 보내면 쿠팡 400 — Number 로 싣는다
        return body;
    }

    private Map<String, Object> rejectionBody(MarketplaceAccount account, long exchangeId,
                                              ClaimActionCommand command) {
        Map<String, Object> body = exchangeBody(account, exchangeId);
        body.put("exchangeRejectCode", validateRejectCode(command.rejectCode()));
        return body;
    }

    /**
     * 거부 사유 검증 — <b>어댑터가 목록의 소유자</b>다(D19).
     *
     * <p>DTO 에 {@code @Pattern}·enum 으로 값을 박으면 화면에 보이는 선택지({@code choices})와
     * 서버가 받는 값이 갈린다. 목록 밖 값은 쿠팡을 치기 전에 400.
     */
    private String validateRejectCode(String rejectCode) {
        String code = (rejectCode == null) ? "" : rejectCode.trim();
        boolean known = EXCHANGE_REJECT_CHOICES.stream()
                .anyMatch(choice -> choice.code().equals(code));
        if (!known) {
            throw new IllegalArgumentException("지원하지 않는 교환 거부 사유입니다: " + rejectCode);
        }
        return code;
    }

    /**
     * X3 재발송 송장 바디.
     *
     * <p>⚠️ 택배사 필드명이 이 액션만 {@code goodsDeliveryCode} 다(문서 그대로 — 오타처럼 보이지만
     * {@code deliveryCompanyCode} 로 보내면 400). <b>값의 출처는 다른 액션과 같다</b>(D16).
     */
    private Map<String, Object> reshipInvoiceBody(MarketplaceAccount account, OrderClaim anchor,
                                                  long exchangeId, ClaimActionCommand command) {
        String deliveryCompanyCode = carrierCodeService.validateDeliveryCompanyCode(
                command.deliveryCompanyCode(), account.getPlatform());

        // 재조회 1회 + 송장 1회 = 이 액션만 클릭당 쿠팡 호출이 2번이다(429 조사 때 보이게 남긴다).
        log.info("Reship invoice: re-querying exchange for the new shipment box (2 Coupang calls): exchangeId={}",
                anchor.getExternalClaimId());
        long shipmentBoxId = resolveReshipBoxId(account, anchor);

        Map<String, Object> body = exchangeBody(account, exchangeId);
        body.put("shipmentBoxId", shipmentBoxId);
        body.put("goodsDeliveryCode", deliveryCompanyCode);
        body.put("invoiceNumber", command.invoiceNumber().trim());
        return body;
    }

    /**
     * 재배송 박스 id 를 <b>전송 직전에</b> 재조회로 얻는다 (D12).
     *
     * 🔴 우리가 저장한 {@code external_box_id}(원 배송번호)로 폴백하지 않는다 — 쿠팡은 200 을 주고
     * 엉뚱한 박스에 송장이 붙어 고객에게 잘못된 추적번호가 나간다. 못 찾으면 400 이다.
     * <p>저장하지도 않는다 — 입고확인부터 발송까지 시차가 있어 미리 담아두면 낡는다.
     */
    private long resolveReshipBoxId(MarketplaceAccount account, OrderClaim claim) {
        if (claim.getReceivedAt() == null) {
            throw new IllegalArgumentException("접수일이 없어 재발송 박스를 조회할 수 없습니다");
        }
        // 재조회 창 = 접수일 하루. createdAtFrom/To 는 접수의 createdAt 기준이고 receivedAt 이 바로 그
        // 값이다(KST 벽시계로 저장돼 있으므로 다시 환산하지 않는다). "최근 7일"이 아니다 —
        // 반송·입고확인·10분 대기를 거치면 접수일은 이미 과거다.
        LocalDate day = claim.getReceivedAt().toLocalDate();
        JsonNode receipt = coupangClaimAdapter
                .findExchangeReceipt(account, claim.getExternalClaimId(), new SyncWindow(day, day))
                .orElse(null);
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "재발송 박스를 찾지 못했습니다. 입고확인 후 10분이 지난 뒤 다시 시도하세요.");
        }

        // ⚠️ item 레벨(exchangeItemDtoV1s[].shipmentBoxId)을 뒤지지 말 것 — 그 값이 원 배송번호다.
        for (JsonNode group : firstPresent(receipt,
                "deliveryInvoiceGroupDtos", "exchangeDeliveryDtos", "deliveryDtos")) {
            String boxId = firstText(group, "shipmentBoxId", "boxId");   // 파서와 같은 alias 규칙
            if (boxId != null && !boxId.equals(claim.getExternalBoxId())) {
                return toShipmentBoxId(boxId);                           // 원 배송번호면 "못 찾은 것"이다
            }
        }
        // 값이 아니라 필드명만 남긴다(PII, D19). "박스가 아직 없다"와 "필드 이름을 틀렸다"를
        // 사용자 화면으로는 구분할 수 없어서, dev 에서 alias 1순위를 확정하는 유일한 단서다.
        log.warn("Reship box not found: exchangeId={} receiptFields={}",
                claim.getExternalClaimId(), fieldNames(receipt));
        throw new IllegalArgumentException(
                "재발송 박스를 찾지 못했습니다. 입고확인 후 10분이 지난 뒤 다시 시도하세요.");
    }

    /** {@code shipmentBoxId} 는 Number 로 보낸다({@code receiptId} 와 같은 이유). */
    private long toShipmentBoxId(String boxId) {
        try {
            return Long.parseLong(boxId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("재발송 박스번호가 숫자가 아닙니다: " + boxId);
        }
    }

    /** 후보 경로를 순서대로 시도해 처음으로 값이 있는 노드를 돌려준다(전부 없으면 missing 노드). */
    private JsonNode firstPresent(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.isEmpty()) {
                return value;
            }
        }
        return node.path(fields[fields.length - 1]);
    }

    /** 후보 경로를 순서대로 시도한 문자열. 부재·null·빈 문자열은 전부 null(파서와 같은 규칙). */
    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String raw = value.asText();
            if (!raw.isBlank()) {
                return raw;
            }
        }
        return null;
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
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
