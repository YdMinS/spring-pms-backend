package com.pms.service.lotteon;

import com.pms.domain.LotteonCancelRequest;
import com.pms.dto.request.LotteonCancelRejectRequest;
import com.pms.dto.response.LotteonCancelRequestResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.LotteonCancelRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LotteonCancelRequestService} implementation.
 *
 * <p>⚠️ No transaction annotation here: LotteON HTTP runs here; the local write after a success goes through
 * {@link LotteonCancelRequestUpserter} (its own transaction).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteonCancelRequestServiceImpl implements LotteonCancelRequestService {

    static final String CLOSED_MESSAGE = "이미 처리된 접수입니다";
    static final String REJECT_REASON_MESSAGE = "거부 사유를 선택하세요";

    private final LotteonCancelRequestRepository cancelRequestRepository;
    private final LotteonCancelRequestUpserter cancelRequestUpserter;
    private final LotteonOrderApi lotteonOrderApi;

    @Override
    public List<LotteonCancelRequestResponse> listOpen(Long orderItemId) {
        return cancelRequestRepository
                .findByOrderLine_IdAndPlatformStatus(orderItemId, LotteonCancelRequest.STATUS_REQUESTED)
                .stream()
                .sorted(Comparator.comparing(LotteonCancelRequest::getRequestedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new LotteonCancelRequestResponse(c.getId(), c.getQuantity(),
                        LotteonCancelReasons.label(c.getReasonCode(), c.getReasonText()), c.getRequestedAt()))
                .toList();
    }

    /** D24: [60] with the request's own {@code clmRsnCd}. */
    @Override
    public void approve(Long requestId) {
        LotteonCancelRequest request = openRequest(requestId);
        lotteonOrderApi.approveCancelRequest(request.getMarketplaceAccount(),
                body(request, item(request, request.getReasonCode(), null)));
        cancelRequestUpserter.markApproved(request.getId());
        log.info("롯데ON 취소 승인: request={} odNo={} clmNo={} qty={}", request.getId(), request.getOdNo(),
                request.getClmNo(), request.getQuantity());
    }

    /** D24: [59] with a listed reason and an optional text. */
    @Override
    public void reject(Long requestId, LotteonCancelRejectRequest request) {
        LotteonCancelRequest claim = openRequest(requestId);
        String code = request.reasonCode() == null ? null : request.reasonCode().trim();
        LotteonSellerChoices.requireLabel(LotteonSellerChoices.CANCEL_REJECT_REASONS, code, REJECT_REASON_MESSAGE);
        String text = request.reasonText() == null || request.reasonText().isBlank()
                ? null : request.reasonText().trim();
        lotteonOrderApi.rejectCancelRequest(claim.getMarketplaceAccount(), body(claim, item(claim, code, text)));
        cancelRequestUpserter.markRejected(claim.getId());
        log.info("롯데ON 취소 거부: request={} odNo={} clmNo={} code={}", claim.getId(), claim.getOdNo(),
                claim.getClmNo(), code);
    }

    /** The request, which must still be open (status 02) — a closed one is a 409. */
    private LotteonCancelRequest openRequest(Long requestId) {
        LotteonCancelRequest request = cancelRequestRepository.findWithAccountById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("LotteonCancelRequest", requestId));
        if (!LotteonCancelRequest.STATUS_REQUESTED.equals(request.getPlatformStatus())) {
            throw new BusinessException(CLOSED_MESSAGE, HttpStatus.CONFLICT);
        }
        return request;
    }

    /** [60] · [59] body: {@code odNo} · {@code clmNo} · one {@code itemList} line. */
    private static Map<String, Object> body(LotteonCancelRequest request, Map<String, Object> item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("odNo", request.getOdNo());
        body.put("clmNo", request.getClmNo());
        body.put("itemList", List.of(item));
        return body;
    }

    /** One {@code itemList} line — a null value is not sent. */
    private static Map<String, Object> item(LotteonCancelRequest request, String reasonCode, String reasonText) {
        Map<String, Object> item = new LinkedHashMap<>();
        putIfPresent(item, "odSeq", request.getOdSeq());
        putIfPresent(item, "procSeq", request.getProcSeq());
        putIfPresent(item, "orglProcSeq", request.getOrglProcSeq());
        putIfPresent(item, "slrRsnCd", reasonCode);
        putIfPresent(item, "slrRsnCnts", reasonText);
        return item;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
