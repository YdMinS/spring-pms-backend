package com.pms.service.claim;

import com.pms.domain.ClaimAction;
import com.pms.domain.ClaimType;
import com.pms.domain.CollectInvoiceSource;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.repository.OrderLineRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.InvoiceNumbers;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstPaths;
import com.pms.service.elevenst.ElevenstText;
import com.pms.service.elevenst.ElevenstTimes;
import com.pms.service.elevenst.ElevenstXml;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 11st return / exchange actions (FEATURE_2610_17 / D15 · D22 · D23 · D24 · D34 · D39). The only place that knows
 * 11st claim status codes ({@code clmStat}).
 *
 * <p>Returns — visible by status (D39 ③): approve 1644 and reject 1645 on 105 · 104 · 109, hold 1654 on 105,
 * completion hold 1655 on 104, pickup invoice 6747 (and its resend) on 105 · 104 · 109 when the claim is linked to an
 * order line — the delivery number is that line's bundle (D22). Exchanges: reject 1650 and approve-with-invoice
 * 1649 (D24) on 201 · 214. Receive-confirm and exchange pickup invoice have no 11st API → never offered (D15).
 *
 * <p>An 11st refusal comes back as a failed outcome with 11st's own text (the service turns it into 502, or into a
 * local record for the pickup invoice — 2609_70 D6). Reason texts go EUC-KR URL-encoded in the path (D39 ②).
 * <p>⚠️ No DB writes here and no {@code @Transactional} (external HTTP) — same as the Coupang adapter.
 */
@Component
@RequiredArgsConstructor
public class ElevenstClaimActionAdapter implements ClaimActionAdapter {

    private static final Set<String> RETURN_OPEN = Set.of("105", "104", "109");
    private static final String RETURN_REQUESTED = "105";
    private static final String RETURN_ON_HOLD = "104";
    private static final Set<String> EXCHANGE_OPEN = Set.of("201", "214");
    private static final String DELIVERY_METHOD_PARCEL = "01";
    private static final Set<String> RESULT_OK = Set.of("0");

    /** D34: return reject reasons (1645 original). */
    static final List<ActionChoice> RETURN_REJECT_CHOICES = List.of(
            new ActionChoice("101", "반품 상품 미입고"),
            new ActionChoice("102", "고객 반품신청 철회 대행"),
            new ActionChoice("103", "반품 불가 상품"),
            new ActionChoice("104", "기타"));
    /** D34: return hold / completion hold reasons (1654 · 1655 original). */
    static final List<ActionChoice> RETURN_HOLD_CHOICES = List.of(
            new ActionChoice("101", "반품 상품 미입고"),
            new ActionChoice("102", "반품 배송비 미동봉"),
            new ActionChoice("103", "반품 상품 훼손"),
            new ActionChoice("104", "구매자 연락 두절"),
            new ActionChoice("105", "기타"));
    /** D15: exchange reject reasons (1650 original). */
    static final List<ActionChoice> EXCHANGE_REJECT_CHOICES = List.of(
            new ActionChoice("201", "교환 상품 미입고"),
            new ActionChoice("202", "고객 교환신청 철회 대행"),
            new ActionChoice("203", "교환 불가 상품"),
            new ActionChoice("204", "기타"));

    /** D34: the gray line under the [반품 거부] reason (1645 says the text reaches the buyer). */
    static final String RETURN_REJECT_HINT = "입력한 사유는 고객에게 문자 · 메일로 전달됩니다.";
    /** D39 ④: the gray line of the [반품완료 보류] form. */
    static final String RETURN_COMPLETE_HOLD_HINT = "반품완료 보류 뒤에도 오래 처리하지 않으면 11번가가 강제로 환불할 수 있습니다.";
    /** D22 · D18: 11st label of the pickup-invoice resend. */
    static final String RESEND_LABEL = "마켓에 다시 보내기";
    /** D24: 11st label of the exchange approve that carries the re-shipping invoice. */
    static final String EXCHANGE_APPROVE_LABEL = "교환 승인(재발송 송장 등록)";

    private final ElevenstApiClient client;
    private final CarrierCodeService carrierCodeService;
    private final OrderLineRepository orderLineRepository;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    @Override
    public List<ClaimActionOption> availableActions(OrderClaim claim, Set<ClaimAction> alreadySucceeded) {
        String status = claim.getPlatformStatus() == null ? "" : claim.getPlatformStatus().trim();
        List<ClaimAction> candidates = new ArrayList<>();
        if (claim.getClaimType() == ClaimType.EXCHANGE) {
            if (EXCHANGE_OPEN.contains(status)) {
                candidates.add(ClaimAction.EXCHANGE_REJECT);
                candidates.add(ClaimAction.EXCHANGE_RESHIP_INVOICE);
            }
        } else if (RETURN_OPEN.contains(status)) {
            candidates.add(ClaimAction.RETURN_APPROVE);
            candidates.add(ClaimAction.RETURN_REJECT);
            if (RETURN_REQUESTED.equals(status)) {
                candidates.add(ClaimAction.RETURN_HOLD);
            }
            if (RETURN_ON_HOLD.equals(status)) {
                candidates.add(ClaimAction.RETURN_COMPLETE_HOLD);
            }
            if (claim.getOrderLine() != null) {
                boolean hasInvoice = claim.getCollectInvoiceNo() != null && !claim.getCollectInvoiceNo().isBlank();
                if (!hasInvoice) {
                    candidates.add(ClaimAction.RETURN_COLLECT_INVOICE);
                } else if (claim.getCollectInvoiceSource() == CollectInvoiceSource.LOCAL) {
                    candidates.add(ClaimAction.RETURN_COLLECT_INVOICE_RESEND);
                }
            }
        }
        return candidates.stream()
                .filter(action -> !alreadySucceeded.contains(action))
                .map(ElevenstClaimActionAdapter::toOption)
                .toList();
    }

    @Override
    public ClaimActionOutcome execute(MarketplaceAccount account, List<OrderClaim> siblings,
                                      ClaimActionCommand command) {
        OrderClaim claim = siblings.get(0);
        Map<String, String> key = new LinkedHashMap<>();
        key.put("clmReqSeq", claim.getExternalClaimId());
        key.put("ordNo", claim.getExternalOrderId());
        key.put("ordPrdSeq", claim.getExternalItemId());
        return switch (command.action()) {
            case RETURN_APPROVE -> send(ElevenstPaths.RETURN_APPROVE, key, account);
            case RETURN_REJECT -> send(ElevenstPaths.RETURN_REJECT, withReason(key, "refsRsnCd", "refsRsn",
                    RETURN_REJECT_CHOICES, command), account);
            case RETURN_HOLD -> send(ElevenstPaths.RETURN_HOLD, withReason(key, "deferRefsRsnCd", "deferRefsRsn",
                    RETURN_HOLD_CHOICES, command), account);
            case RETURN_COMPLETE_HOLD -> send(ElevenstPaths.RETURN_COMPLETE_HOLD, withReason(key, "deferRefsRsnCd",
                    "deferRefsRsn", RETURN_HOLD_CHOICES, command), account);
            case RETURN_COLLECT_INVOICE -> collectInvoice(account, claim, key, command.deliveryCompanyCode(),
                    command.invoiceNumber());
            case RETURN_COLLECT_INVOICE_RESEND -> collectInvoice(account, claim, key, claim.getCollectCarrierCode(),
                    claim.getCollectInvoiceNo());
            case EXCHANGE_REJECT -> send(ElevenstPaths.EXCHANGE_REJECT, withReason(key, "refsRsnCd", "refsRsn",
                    EXCHANGE_REJECT_CHOICES, command), account);
            case EXCHANGE_RESHIP_INVOICE -> {
                key.put("dlvEtprsCd", carrierCodeService.validateDeliveryCompanyCode(command.deliveryCompanyCode(),
                        Platform.ELEVENST));
                key.put("invcNo", InvoiceNumbers.normalizeRequired(command.invoiceNumber()));
                yield send(ElevenstPaths.EXCHANGE_APPROVE, key, account);
            }
            default -> throw new UnsupportedOperationException(
                    "11번가 어댑터가 지원하지 않는 액션입니다: " + command.action());
        };
    }

    /** 6747 — the delivery number is the linked order line's bundle (D22); 11st's refusal comes back as failed. */
    private ClaimActionOutcome collectInvoice(MarketplaceAccount account, OrderClaim claim, Map<String, String> key,
                                              String carrierCode, String invoiceNumber) {
        if (claim.getOrderLine() == null) {
            throw new IllegalArgumentException("주문이 연결되지 않은 반품은 회수 송장을 등록할 수 없습니다");
        }
        OrderLine line = orderLineRepository.findWithAccountAndSellerById(claim.getOrderLine().getId())
                .orElseThrow(() -> new IllegalArgumentException("주문 라인을 찾을 수 없습니다"));
        if (line.getOrderShipment() == null) {
            throw new IllegalArgumentException("박스 ID가 없습니다. 주문동기화 후 다시 시도하세요");
        }
        key.put("sendDt", ElevenstTimes.MINUTE.format(LocalDateTime.now(SyncWindow.KST)));
        key.put("dlvNo", line.getOrderShipment().getExternalShipmentId());
        key.put("dlvEtprsCd", carrierCodeService.validateDeliveryCompanyCode(carrierCode, Platform.ELEVENST));
        key.put("invcNo", InvoiceNumbers.normalizeRequired(invoiceNumber));
        key.put("dlvMthdCd", DELIVERY_METHOD_PARCEL);
        return send(ElevenstPaths.RETURN_COLLECT_INVOICE, key, account);
    }

    /** Adds the checked reason code + the EUC-KR-encoded reason text (D34 · D39 ①②). */
    private static Map<String, String> withReason(Map<String, String> key, String codeField, String textField,
                                                  List<ActionChoice> choices, ClaimActionCommand command) {
        String code = command.rejectCode() == null ? "" : command.rejectCode().trim();
        if (choices.stream().noneMatch(choice -> choice.code().equals(code))) {
            throw new IllegalArgumentException("지원하지 않는 사유입니다: " + command.rejectCode());
        }
        key.put(codeField, code);
        key.put(textField, ElevenstText.pathSegment(ElevenstText.requireReason(command.reasonText())));
        return key;
    }

    /** Calls the path (segments already encoded) and reads {@code result_code}: 0 = success, else 11st's text. */
    private ClaimActionOutcome send(String template, Map<String, String> values, MarketplaceAccount account) {
        byte[] raw = client.getPreEncoded(ElevenstPaths.fill(template, values), account);
        Document doc = ElevenstXml.parseOrNull(raw);
        String code = ElevenstXml.firstText(doc, "result_code");
        String text = ElevenstXml.firstText(doc, "result_text");
        boolean ok = code != null && RESULT_OK.contains(code);
        return new ClaimActionOutcome(ok, code, (text == null || text.isBlank()) ? ElevenstXml.messageOf(raw) : text);
    }

    private static ClaimActionOption toOption(ClaimAction action) {
        return switch (action) {
            case RETURN_REJECT -> new ClaimActionOption(action, action.getLabel(), ClaimAction.Requires.REASON,
                    RETURN_REJECT_CHOICES, true, RETURN_REJECT_HINT);
            case RETURN_HOLD -> new ClaimActionOption(action, action.getLabel(), ClaimAction.Requires.REASON,
                    RETURN_HOLD_CHOICES, false, null);
            case RETURN_COMPLETE_HOLD -> new ClaimActionOption(action, action.getLabel(), ClaimAction.Requires.REASON,
                    RETURN_HOLD_CHOICES, false, RETURN_COMPLETE_HOLD_HINT);
            case EXCHANGE_REJECT -> new ClaimActionOption(action, action.getLabel(), ClaimAction.Requires.REASON,
                    EXCHANGE_REJECT_CHOICES, true, null);
            case EXCHANGE_RESHIP_INVOICE -> new ClaimActionOption(action, EXCHANGE_APPROVE_LABEL,
                    ClaimAction.Requires.INVOICE, List.of(), true, null);
            case RETURN_COLLECT_INVOICE_RESEND -> new ClaimActionOption(action, RESEND_LABEL,
                    ClaimAction.Requires.NONE, List.of(), false, null);
            default -> new ClaimActionOption(action, action.getLabel(), action.getRequires(), List.of(),
                    action.isIrreversible(), null);
        };
    }
}
