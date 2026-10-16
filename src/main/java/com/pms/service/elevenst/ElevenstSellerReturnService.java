package com.pms.service.elevenst;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.ElevenstSellerReturnRequest;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.InvoiceNumbers;
import com.pms.service.claim.ActionChoice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [판매자가 대신 반품 신청] for an 11st order line (FEATURE_2610_17 / D35 · D36 · D39 ⑤ — API 1653).
 *
 * <ul>
 *   <li>Only a shipped / delivering / delivered 11st line that is not fully cancelled (D35 · D39 ⑤).</li>
 *   <li>Reason = one of the 13 original codes + 1~200 characters of text (EUC-KR URL-encoded, D39 ①②).</li>
 *   <li>Carrier and invoice: both → delivery method 01 parcel; neither → the three path values are {@code null};
 *       only one → 400 「택배사와 송장번호를 함께 넣어 주세요.」.</li>
 *   <li>{@code completeNow} → {@code claimProcess} 02 (request + complete), else 01 (D36).</li>
 * </ul>
 * The 11st response carries no claim number — the return appears on the claim screen after the next sync.
 * <p>⚠️ No {@code @Transactional} (external HTTP) and no DB write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElevenstSellerReturnService {

    /** D39 ⑤: the 13 reason codes of 1653 with their original names. */
    public static final List<ActionChoice> REASONS = List.of(
            new ActionChoice("101", "상품에 이상 없으나 구매 의사 없어짐"),
            new ActionChoice("105", "상품이 상품상세 정보와 틀림"),
            new ActionChoice("108", "다른 상품이 잘못 배송됨"),
            new ActionChoice("110", "사이즈, 색상 등을 잘못 선택함"),
            new ActionChoice("111", "배송된 상품의 파손/하자/포장 불량"),
            new ActionChoice("112", "상품이 도착하고 있지 않음"),
            new ActionChoice("113", "기타(구매자 책임사유)"),
            new ActionChoice("116", "기타(판매자 책임사유)"),
            new ActionChoice("121", "구매하고 싶지 않거나 색상/사이즈 잘못 선택"),
            new ActionChoice("122", "배송된 상품 파손/하자/불량"),
            new ActionChoice("123", "배송지연, 상품 품질 문제 등"),
            new ActionChoice("125", "상품 구성 중 일부가 배송되지 않음"),
            new ActionChoice("126", "품절 안내를 받음"));

    /** 11st −21004 wording, used before calling when the line is not eligible. */
    static final String NOT_ELIGIBLE_MESSAGE = "반품신청 가능한 상태가 아닙니다";
    static final String CARRIER_AND_INVOICE_TOGETHER_MESSAGE = "택배사와 송장번호를 함께 넣어 주세요.";
    private static final Set<String> FEE_METHODS = Set.of("03", "04");
    private static final Set<OrderStatus> SHIPPED_STATUSES =
            Set.of(OrderStatus.SHIPPED, OrderStatus.DELIVERING, OrderStatus.DELIVERED);
    private static final String DELIVERY_METHOD_PARCEL = "01";
    private static final String NONE = "null";

    private final OrderLineRepository orderLineRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final CarrierCodeService carrierCodeService;
    private final ElevenstApiClient client;

    /**
     * @throws ResourceNotFoundException no such order line (→ 404)
     * @throws IllegalArgumentException  not eligible · input rule broken (→ 400)
     * @throws com.pms.exception.BusinessException 11st refused (→ 502 with 11st's text)
     */
    public void request(Long orderItemId, ElevenstSellerReturnRequest request) {
        OrderLine line = orderLineRepository.findWithAccountAndSellerById(orderItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderItemId));
        MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
        if (!Platform.ELEVENST.equals(account.getPlatform()) || line.getStatus() == null
                || !SHIPPED_STATUSES.contains(line.getStatus()) || line.isFullyCancelled()) {
            throw new IllegalArgumentException(NOT_ELIGIBLE_MESSAGE);
        }
        ElevenstOrderLine mirror = elevenstOrderLineRepository.findByOrderLine_Id(line.getId())
                .orElseThrow(() -> new IllegalArgumentException(NOT_ELIGIBLE_MESSAGE));
        String code = request.reasonCode() == null ? "" : request.reasonCode().trim();
        if (REASONS.stream().noneMatch(reason -> reason.code().equals(code))) {
            throw new IllegalArgumentException("지원하지 않는 사유입니다: " + request.reasonCode());
        }
        if (!FEE_METHODS.contains(request.shippingFeeMethod())) {
            throw new IllegalArgumentException("반품 배송비 전달 방법을 선택하세요");
        }
        boolean hasCarrier = request.deliveryCompanyCode() != null && !request.deliveryCompanyCode().isBlank();
        boolean hasInvoice = request.invoiceNumber() != null && !request.invoiceNumber().isBlank();
        if (hasCarrier != hasInvoice) {
            throw new IllegalArgumentException(CARRIER_AND_INVOICE_TOGETHER_MESSAGE);
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ordNo", mirror.getOrdNo());
        values.put("ordPrdSeq", mirror.getOrdPrdSeq());
        values.put("clmReqRsn", code);
        values.put("claimProcess", request.completeNow() ? "02" : "01");
        values.put("dlvEtprsCd", hasCarrier
                ? carrierCodeService.validateDeliveryCompanyCode(request.deliveryCompanyCode(), Platform.ELEVENST)
                : NONE);
        values.put("invcNo", hasInvoice ? InvoiceNumbers.normalizeRequired(request.invoiceNumber()) : NONE);
        values.put("dlvMthdCd", hasCarrier ? DELIVERY_METHOD_PARCEL : NONE);
        values.put("clmReqCont", ElevenstText.pathSegment(ElevenstText.requireReason(request.reasonText())));
        values.put("clmDlvCstMthd", request.shippingFeeMethod());
        ElevenstOrderApi.requireResult(client.getPreEncoded(ElevenstPaths.fill(ElevenstPaths.SELLER_RETURN, values),
                account), Set.of("0"));
        log.info("11번가 대리 반품신청: orderItemId={} ordNo={} completeNow={}", orderItemId, mirror.getOrdNo(),
                request.completeNow());
    }
}
