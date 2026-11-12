package com.pms.service.claim;

import com.pms.domain.ClaimAction;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.LotteonClaim;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.Platform;
import com.pms.exception.BusinessException;
import com.pms.repository.LotteonClaimRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.InvoiceNumbers;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderApi.LineFailure;
import com.pms.service.lotteon.LotteonSellerChoices;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LotteON return / exchange actions (FEATURE_2611_23 / D28 · D45 ~ D47 · D69 · D73 · D80 · R33 ~ R37). The only place
 * that knows LotteON claim steps ({@code odPrgsStepCd} 23 ~ 27) and the hold status.
 *
 * <p>Buttons ({@link #availableActions}) — step 23 without pickup invoice → [회수 송장 등록]; 23 · 24 with one →
 * [입고확인]; 23 · 24 · 25 not on hold → [보류]; on hold → [보류 해제]; 25 with a pickup invoice → [반품 승인] /
 * [교환 회수 확정]; an open (RECEIVED · IN_PROGRESS) exchange with a re-ship line and no re-ship invoice yet →
 * [재발송 송장 등록] (D90). Reads {@code lotteon_claim} once per claim (R37). Hold / release are repeatable (D80) and never dropped as "already succeeded".
 *
 * <p>Sending ({@link #execute}) — every sibling line of the receipt ({@code clmNo}, R33): [210] Y once per line
 * (stamped right after success, R36) → [298] retrieval 24 · 25 · 26 or delivery 13; hold / release = [141] 03 / 09.
 * A LotteON refusal is <b>thrown</b> (502 with LotteON's text), never a failed outcome — so a refused pickup invoice
 * is not kept in our ledger (D69 · R34).
 *
 * <p>⚠️ No transaction annotation here (external HTTP); the {@code lotteon_claim} writes go through
 * {@link LotteonClaimUpserter}'s {@code mark…} methods, one commit each.
 */
@Component
@RequiredArgsConstructor
public class LotteonClaimActionAdapter implements ClaimActionAdapter {

    static final String ON_HOLD_NOTE = "보류 중";
    static final String NO_LOTTEON_CLAIM_MESSAGE = "롯데ON 클레임 정보가 없습니다. 동기화 후 다시 시도하세요";
    static final String NO_RESHIP_LINE_MESSAGE = "교환 재발송 출고지시가 아직 없습니다";

    private static final String RETRIEVAL = "RTRV";
    private static final String DELIVERY = "DV";
    private static final String STEP_COLLECTING = "24";
    private static final String STEP_RECEIVED = "25";
    private static final String STEP_CONFIRMED = "26";
    private static final String STEP_SHIPPED = "13";
    private static final String SUCCESS = "0000";
    private static final Set<String> RECEIVE_STEPS = Set.of("23", "24");
    private static final Set<String> HOLD_STEPS = Set.of("23", "24", "25");
    private static final Set<ClaimStatus> RESHIP_STATUSES = Set.of(ClaimStatus.RECEIVED, ClaimStatus.IN_PROGRESS);

    private static final Set<ClaimAction> REPEATABLE = Set.of(ClaimAction.RETURN_HOLD,
            ClaimAction.RETURN_HOLD_RELEASE, ClaimAction.EXCHANGE_HOLD, ClaimAction.EXCHANGE_HOLD_RELEASE);

    private final LotteonOrderApi lotteonOrderApi;
    private final LotteonClaimRepository lotteonClaimRepository;
    private final LotteonClaimUpserter lotteonClaimUpserter;
    private final CarrierCodeService carrierCodeService;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    @Override
    public Set<ClaimAction> repeatableActions() {
        return REPEATABLE;
    }

    @Override
    public List<ClaimActionOption> availableActions(OrderClaim claim, Set<ClaimAction> alreadySucceeded) {
        if (claim.getStatus() == null || !claim.getStatus().isOpen()) {
            return List.of();
        }
        Optional<LotteonClaim> found = lotteonClaimRepository.findByOrderClaim_Id(claim.getId());
        if (found.isEmpty()) {
            return List.of();
        }
        LotteonClaim lotteon = found.get();
        String step = claim.getPlatformStatus() == null ? "" : claim.getPlatformStatus().trim();
        boolean invoice = claim.getCollectInvoiceNo() != null && !claim.getCollectInvoiceNo().isBlank();
        boolean hold = LotteonClaim.HOLD.equals(lotteon.getHoldStatus());
        boolean exchange = claim.getClaimType() == ClaimType.EXCHANGE;
        boolean reshipInvoice = claim.getReshipInvoiceNo() != null && !claim.getReshipInvoiceNo().isBlank();

        List<ClaimAction> candidates = new ArrayList<>();
        if ("23".equals(step) && !invoice) {
            candidates.add(exchange ? ClaimAction.EXCHANGE_COLLECT_INVOICE : ClaimAction.RETURN_COLLECT_INVOICE);
        }
        if (RECEIVE_STEPS.contains(step) && invoice) {
            candidates.add(exchange ? ClaimAction.EXCHANGE_RECEIVE_CONFIRM : ClaimAction.RETURN_RECEIVE_CONFIRM);
        }
        if (HOLD_STEPS.contains(step) && !hold) {
            candidates.add(exchange ? ClaimAction.EXCHANGE_HOLD : ClaimAction.RETURN_HOLD);
        }
        if (hold) {
            candidates.add(exchange ? ClaimAction.EXCHANGE_HOLD_RELEASE : ClaimAction.RETURN_HOLD_RELEASE);
        }
        if (STEP_RECEIVED.equals(step) && invoice) {
            candidates.add(exchange ? ClaimAction.EXCHANGE_COLLECT_CONFIRM : ClaimAction.RETURN_APPROVE);
        }
        if (exchange && RESHIP_STATUSES.contains(claim.getStatus()) && lotteon.getReshipOdSeq() != null
                && !reshipInvoice) {
            candidates.add(ClaimAction.EXCHANGE_RESHIP_INVOICE);
        }
        return candidates.stream()
                .filter(action -> REPEATABLE.contains(action) || !alreadySucceeded.contains(action))
                .map(LotteonClaimActionAdapter::toOption)
                .toList();
    }

    @Override
    public ClaimActionOutcome execute(MarketplaceAccount account, List<OrderClaim> siblings,
                                      ClaimActionCommand command) {
        Map<Long, LotteonClaim> rows = lotteonClaimRepository
                .findByOrderClaim_IdIn(siblings.stream().map(OrderClaim::getId).toList()).stream()
                .collect(Collectors.toMap(row -> row.getOrderClaim().getId(), Function.identity(), (a, b) -> a));
        if (siblings.stream().anyMatch(sibling -> !rows.containsKey(sibling.getId()))) {
            throw new IllegalArgumentException(NO_LOTTEON_CLAIM_MESSAGE);
        }
        List<Long> ids = siblings.stream().map(OrderClaim::getId).toList();
        switch (command.action()) {
            case RETURN_COLLECT_INVOICE, EXCHANGE_COLLECT_INVOICE -> {
                String carrier = carrierCodeService.validateDeliveryCompanyCode(command.deliveryCompanyCode(),
                        Platform.LOTTEON);
                String invoice = InvoiceNumbers.normalizeRequired(command.invoiceNumber());
                informRetrieval(account, siblings, rows);
                progress(account, retrievalProgressLines(siblings, rows, STEP_COLLECTING, carrier, invoice));
            }
            case RETURN_RECEIVE_CONFIRM, EXCHANGE_RECEIVE_CONFIRM -> {
                informRetrieval(account, siblings, rows);
                OrderClaim withInvoice = firstWithCollectInvoice(siblings);
                progress(account, retrievalProgressLines(siblings, rows, STEP_RECEIVED,
                        carrierOf(withInvoice), invoiceOf(withInvoice)));
            }
            case RETURN_APPROVE, EXCHANGE_COLLECT_CONFIRM -> {
                OrderClaim withInvoice = firstWithCollectInvoice(siblings);
                progress(account, retrievalProgressLines(siblings, rows, STEP_CONFIRMED,
                        carrierOf(withInvoice), invoiceOf(withInvoice)));
            }
            case RETURN_HOLD, EXCHANGE_HOLD -> {
                LotteonSellerChoices.requireLabel(LotteonSellerChoices.HOLD_REASONS, command.rejectCode(),
                        "지원하지 않는 사유입니다: " + command.rejectCode());
                lotteonOrderApi.informRetrievalException(account, exceptionLines(siblings, rows, LotteonClaim.HOLD,
                        command.rejectCode().trim(), command.reasonText()));
                lotteonClaimUpserter.markHold(ids, LotteonClaim.HOLD);
            }
            case RETURN_HOLD_RELEASE, EXCHANGE_HOLD_RELEASE -> {
                // D78: text only — the reason code is ignored even when sent.
                lotteonOrderApi.informRetrievalException(account, exceptionLines(siblings, rows,
                        LotteonClaim.HOLD_RELEASED, null, command.reasonText()));
                lotteonClaimUpserter.markHold(ids, LotteonClaim.HOLD_RELEASED);
            }
            case EXCHANGE_RESHIP_INVOICE -> reship(account, siblings, rows, command);
            default -> throw new UnsupportedOperationException(
                    "롯데ON 어댑터가 지원하지 않는 액션입니다: " + command.action());
        }
        return new ClaimActionOutcome(true, SUCCESS, null);
    }

    /** D79: 「보류 중」 for claims whose {@code hold_status} is 03 — one query. */
    @Override
    public Map<Long, String> statusNotes(List<OrderClaim> claims) {
        if (claims.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> notes = new HashMap<>();
        for (LotteonClaim row : lotteonClaimRepository
                .findByOrderClaim_IdIn(claims.stream().map(OrderClaim::getId).toList())) {
            if (LotteonClaim.HOLD.equals(row.getHoldStatus())) {
                notes.put(row.getOrderClaim().getId(), ON_HOLD_NOTE);
            }
        }
        return notes;
    }

    /** [210] retrieval Y for the lines not sent yet, stamped right after success (D30 · R36). */
    private void informRetrieval(MarketplaceAccount account, List<OrderClaim> siblings, Map<Long, LotteonClaim> rows) {
        List<OrderClaim> pending = siblings.stream()
                .filter(sibling -> rows.get(sibling.getId()).getRetrievalInformedAt() == null)
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderClaim claim : pending) {
            LotteonClaim row = rows.get(claim.getId());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("dvRtrvDvsCd", RETRIEVAL);
            put(line, "odNo", claim.getExternalOrderId());
            put(line, "odSeq", claim.getExternalItemId());
            put(line, "procSeq", row.getProcSeq());
            put(line, "orglProcSeq", row.getOrglProcSeq());
            put(line, "clmNo", claim.getExternalClaimId());
            line.put("ifCplYN", "Y");
            lines.add(line);
        }
        lotteonOrderApi.informIfComplete(account, lines);
        lotteonClaimUpserter.markRetrievalInformed(pending.stream().map(OrderClaim::getId).toList(),
                LocalDateTime.now(SyncWindow.KST));
    }

    /** [298] retrieval lines of every sibling (R33). */
    private static List<Map<String, Object>> retrievalProgressLines(List<OrderClaim> siblings,
                                                                    Map<Long, LotteonClaim> rows, String step,
                                                                    String carrier, String invoice) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderClaim claim : siblings) {
            LotteonClaim row = rows.get(claim.getId());
            lines.add(progressLine(RETRIEVAL, claim.getExternalOrderId(), claim.getExternalItemId(), row.getProcSeq(),
                    row.getOrglProcSeq(), claim.getExternalClaimId(), step, carrier, invoice, claim.getQuantity()));
        }
        return lines;
    }

    /** D28 ⑥: [210] delivery Y (once) → [298] delivery 13 on the re-ship lines (D67 · R33). */
    private void reship(MarketplaceAccount account, List<OrderClaim> siblings, Map<Long, LotteonClaim> rows,
                        ClaimActionCommand command) {
        String carrier = carrierCodeService.validateDeliveryCompanyCode(command.deliveryCompanyCode(),
                Platform.LOTTEON);
        String invoice = InvoiceNumbers.normalizeRequired(command.invoiceNumber());
        List<OrderClaim> reshipLines = siblings.stream()
                .filter(sibling -> rows.get(sibling.getId()).getReshipOdSeq() != null)
                .toList();
        if (reshipLines.isEmpty()) {
            throw new IllegalArgumentException(NO_RESHIP_LINE_MESSAGE);
        }
        List<OrderClaim> pending = reshipLines.stream()
                .filter(line -> rows.get(line.getId()).getReshipInformedAt() == null)
                .toList();
        if (!pending.isEmpty()) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (OrderClaim claim : pending) {
                LotteonClaim row = rows.get(claim.getId());
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("dvRtrvDvsCd", DELIVERY);
                put(line, "odNo", claim.getExternalOrderId());
                put(line, "odSeq", row.getReshipOdSeq());
                put(line, "procSeq", row.getReshipProcSeq());
                put(line, "orglProcSeq", row.getOrglProcSeq());
                put(line, "clmNo", claim.getExternalClaimId());
                line.put("ifCplYN", "Y");
                lines.add(line);
            }
            lotteonOrderApi.informIfComplete(account, lines);
            lotteonClaimUpserter.markReshipInformed(pending.stream().map(OrderClaim::getId).toList(),
                    LocalDateTime.now(SyncWindow.KST));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderClaim claim : reshipLines) {
            LotteonClaim row = rows.get(claim.getId());
            lines.add(progressLine(DELIVERY, claim.getExternalOrderId(), row.getReshipOdSeq(), row.getReshipProcSeq(),
                    row.getOrglProcSeq(), claim.getExternalClaimId(), STEP_SHIPPED, carrier, invoice,
                    claim.getQuantity()));
        }
        progress(account, lines);
    }

    /** [141] lines of every sibling — hold (03, with a reason code) or release (09, no code). */
    private static List<Map<String, Object>> exceptionLines(List<OrderClaim> siblings, Map<Long, LotteonClaim> rows,
                                                            String kind, String reasonCode, String reasonText) {
        String text = reasonText == null ? null : reasonText.trim();
        String now = LotteonOrderApi.TIME.format(LocalDateTime.now(SyncWindow.KST));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderClaim claim : siblings) {
            LotteonClaim row = rows.get(claim.getId());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("dvRtrvDvsCd", RETRIEVAL);
            put(line, "odNo", claim.getExternalOrderId());
            put(line, "odSeq", claim.getExternalItemId());
            put(line, "procSeq", row.getProcSeq());
            put(line, "orglProcSeq", row.getOrglProcSeq());
            put(line, "clmNo", claim.getExternalClaimId());
            line.put("excpProcDvsCd", kind);
            put(line, "holdRsnDvsCd", reasonCode);
            put(line, "excpProcRsnCnts", text);
            line.put("dvTrcStatDttm", now);
            lines.add(line);
        }
        return lines;
    }

    /** One [298] line; null fields are not sent. */
    private static Map<String, Object> progressLine(String kind, String odNo, String odSeq, String procSeq,
                                                    String orglProcSeq, String clmNo, String step, String carrier,
                                                    String invoice, Integer quantity) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("dvRtrvDvsCd", kind);
        put(line, "odNo", odNo);
        put(line, "odSeq", odSeq);
        put(line, "procSeq", procSeq);
        put(line, "orglProcSeq", orglProcSeq);
        put(line, "clmNo", clmNo);
        line.put("odPrgsStepCd", step);
        line.put("dvTrcStatDttm", LotteonOrderApi.TIME.format(LocalDateTime.now(SyncWindow.KST)));
        line.put("invcNbr", "1");
        put(line, "dvCoCd", carrier);
        if (invoice != null) {
            line.put("invcNoList", List.of(invoice));
        }
        put(line, "slQty", quantity == null ? null : String.valueOf(quantity));
        return line;
    }

    /** [298] — a line in {@code failList} is a refusal: 502 with the first line's LotteON text (R34). */
    private void progress(MarketplaceAccount account, List<Map<String, Object>> lines) {
        List<LineFailure> failures = lotteonOrderApi.informProgress(account, lines);
        if (!failures.isEmpty()) {
            throw new BusinessException(failures.get(0).message(), HttpStatus.BAD_GATEWAY);
        }
    }

    private static OrderClaim firstWithCollectInvoice(List<OrderClaim> siblings) {
        return siblings.stream()
                .filter(sibling -> sibling.getCollectInvoiceNo() != null && !sibling.getCollectInvoiceNo().isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String carrierOf(OrderClaim claim) {
        return claim == null ? null : claim.getCollectCarrierCode();
    }

    private static String invoiceOf(OrderClaim claim) {
        return claim == null ? null : claim.getCollectInvoiceNo();
    }

    private static void put(Map<String, Object> line, String field, String value) {
        if (value != null) {
            line.put(field, value);
        }
    }

    private static ClaimActionOption toOption(ClaimAction action) {
        boolean hold = action == ClaimAction.RETURN_HOLD || action == ClaimAction.EXCHANGE_HOLD;
        return new ClaimActionOption(action, action.getLabel(), action.getRequires(),
                hold ? LotteonSellerChoices.HOLD_REASONS : List.of(), action.isIrreversible(), null);
    }
}
