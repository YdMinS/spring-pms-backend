package com.pms.service.elevenst;

import com.pms.domain.ElevenstCancelRequest;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.ElevenstCancelFaultRejectRequest;
import com.pms.dto.request.ElevenstCancelRejectRequest;
import com.pms.dto.response.ElevenstCancelRequestResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ElevenstCancelRequestRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.InvoiceNumbers;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * {@link ElevenstCancelRequestService} implementation.
 *
 * <p>⚠️ No {@code @Transactional}: 11st HTTP runs here; the local write after a success goes through
 * {@link ElevenstCancelRequestUpserter} (its own transaction).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElevenstCancelRequestServiceImpl implements ElevenstCancelRequestService {

    /** 11st −23005 「송장번호가 10자리 이하입니다」 — checked before calling (D38). */
    static final String INVOICE_TOO_SHORT_MESSAGE = "송장번호가 10자리 이하입니다.";
    private static final int INVOICE_MIN_EXCLUSIVE = 10;
    /** {@code requested_by} 01 = the buyer filed the request (D62). */
    private static final String BUYER = "01";

    private final ElevenstCancelRequestRepository cancelRequestRepository;
    private final ElevenstCancelRequestUpserter cancelRequestUpserter;
    private final ElevenstOrderActions elevenstOrderActions;
    private final CarrierCodeService carrierCodeService;
    /** FEATURE_2610_17 / D62: the line status decides [책임사유 오류로 거부]; 1745 is sent through here. */
    private final OrderLineRepository orderLineRepository;
    private final ElevenstSellerActions elevenstSellerActions;

    @Override
    public List<ElevenstCancelRequestResponse> listOpen(Long orderItemId) {
        boolean linePaid = orderLineRepository.findById(orderItemId)
                .map(line -> line.getStatus() == OrderStatus.PAID)
                .orElse(false);
        return cancelRequestRepository
                .findByOrderLine_IdInAndPlatformStatus(List.of(orderItemId), ElevenstCancelRequest.STATUS_REQUESTED)
                .stream()
                .sorted(Comparator.comparing(ElevenstCancelRequest::getRequestedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new ElevenstCancelRequestResponse(c.getId(), c.getQuantity(),
                        ElevenstCancelReasons.label(c.getReasonCode(), c.getRequestedBy(), c.getReasonText()),
                        c.getRequestedAt(),
                        linePaid && BUYER.equals(c.getRequestedBy())))
                .toList();
    }

    @Override
    public void approve(Long requestId) {
        ElevenstCancelRequest claim = openClaim(requestId);
        elevenstOrderActions.approveCancel(claim.getMarketplaceAccount(), claim);
        cancelRequestUpserter.markApproved(claim.getId(), LocalDateTime.now(SyncWindow.KST));
        log.info("11번가 취소 승인: request={} ordNo={} qty={}", claim.getId(), claim.getOrdNo(), claim.getQuantity());
    }

    @Override
    public void reject(Long requestId, ElevenstCancelRejectRequest request) {
        ElevenstCancelRequest claim = openClaim(requestId);
        String carrierCode = carrierCodeService.validateDeliveryCompanyCode(request.deliveryCompanyCode(),
                Platform.ELEVENST);
        String invoiceNumber = InvoiceNumbers.normalizeRequired(request.invoiceNumber());
        if (invoiceNumber.length() <= INVOICE_MIN_EXCLUSIVE) {
            throw new IllegalArgumentException(INVOICE_TOO_SHORT_MESSAGE);
        }
        LocalDate sentOn = request.sendDate() != null ? request.sendDate() : LocalDate.now(SyncWindow.KST);
        elevenstOrderActions.rejectCancel(claim.getMarketplaceAccount(), claim, carrierCode, invoiceNumber, sentOn);
        cancelRequestUpserter.markRejected(claim.getId());
        log.info("11번가 취소 거부: request={} ordNo={}", claim.getId(), claim.getOrdNo());
    }

    @Override
    public void rejectFault(Long requestId, ElevenstCancelFaultRejectRequest request) {
        ElevenstCancelRequest claim = openClaim(requestId);
        String refuseCode = request.refuseCode().trim();
        ElevenstSellerChoices.requireLabel(ElevenstSellerChoices.FAULT_REJECT_REASONS, refuseCode,
                "거부 사유를 선택하세요");
        String reasonText = ElevenstText.requireReason(request.reasonText());
        String carrierCode = carrierCodeService.validateDeliveryCompanyCode(request.deliveryCompanyCode(),
                Platform.ELEVENST);
        String invoiceNumber = InvoiceNumbers.normalizeRequired(request.invoiceNumber());
        if (invoiceNumber.length() <= INVOICE_MIN_EXCLUSIVE) {
            throw new IllegalArgumentException(INVOICE_TOO_SHORT_MESSAGE);
        }
        LocalDate sentOn = request.sendDate() != null ? request.sendDate() : LocalDate.now(SyncWindow.KST);
        elevenstSellerActions.rejectCancelFault(claim.getMarketplaceAccount(), claim, carrierCode, invoiceNumber,
                sentOn, refuseCode, reasonText);
        cancelRequestUpserter.markRejected(claim.getId());
        log.info("11번가 책임사유 오류 거부: request={} ordNo={} code={}", claim.getId(), claim.getOrdNo(), refuseCode);
    }

    /** The claim, which must still be open (status 01) — a closed one is a 409 (same wording as claim actions). */
    private ElevenstCancelRequest openClaim(Long requestId) {
        ElevenstCancelRequest claim = cancelRequestRepository.findWithAccountById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("ElevenstCancelRequest", requestId));
        if (!ElevenstCancelRequest.STATUS_REQUESTED.equals(claim.getPlatformStatus())) {
            throw new BusinessException("이미 처리된 접수입니다", HttpStatus.CONFLICT);
        }
        return claim;
    }
}
