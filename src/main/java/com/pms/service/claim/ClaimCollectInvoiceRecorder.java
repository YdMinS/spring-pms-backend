package com.pms.service.claim;

import com.pms.domain.CollectInvoiceSource;
import com.pms.domain.OrderClaim;
import com.pms.repository.OrderClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 회수 송장 값을 접수의 <b>형제 라인 전체</b>에 기록한다 (FEATURE_2609_70 / D6·D10~D12).
 *
 * ⚠️ {@code REQUIRES_NEW} — 호출자({@link ClaimActionServiceImpl})는 외부 HTTP 를 도는 경로라
 * 트랜잭션이 없다. 쓰기만 짧게 자체 트랜잭션으로 커밋한다({@code ClaimUpserter}·
 * {@code InquiryReplyRecorder} 와 같은 관례).
 *
 * <p>🔴 <b>{@code status}·{@code platformStatus} 는 건드리지 않는다</b>(D11 — 2609_21 D7 의
 * 부분 번복). D7 이 금지한 것은 "우리가 추측한 다음 상태"이고, 회수 송장은 추측이 아니라 방금
 * 사용자가 입력한 값이다.
 *
 * <p>🔴 기록 실패를 삼키지 않는다 — 여기서 조용히 실패하면 사용자는 "저장됐다"고 믿는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimCollectInvoiceRecorder {

    private final OrderClaimRepository orderClaimRepository;

    /**
     * 형제 라인 전체에 회수 송장·택배사·출처를 쓴다. 회수 송장은 <b>접수 단위 값</b>이므로
     * 클릭한 라인만 갱신하면 같은 접수의 다른 줄이 "미입력"으로 남는다(D10).
     *
     * @param carrierCode   요청의 {@code deliveryCompanyCode} — <b>이미 마켓 코드</b>다
     *                      (2609_11 D2 개정 · {@code CarrierCatalog} 화이트리스트가 이미 걸렀고
     *                      어댑터가 전송 직전에 한 번 더 검증했다). 여기서 변환하지 않는다
     * @param source        쿠팡이 받아주면 {@code PLATFORM}, 거절해 우리만 기록하면 {@code LOCAL}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(List<OrderClaim> siblings, String carrierCode, String invoiceNumber,
                       CollectInvoiceSource source) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new IllegalArgumentException("회수 송장번호가 비어 있어 기록할 수 없습니다");
        }
        String invoice = invoiceNumber.trim();
        String carrier = (carrierCode == null || carrierCode.isBlank()) ? null : carrierCode.trim();

        List<OrderClaim> updated = new ArrayList<>();
        for (OrderClaim claim : siblings) {
            updated.add(claim.toBuilder()
                    .collectInvoiceNo(invoice)
                    .collectCarrierCode(carrier)
                    .collectInvoiceSource(source)
                    .build());
        }
        orderClaimRepository.saveAll(updated);

        log.info("회수 송장 기록: receipt={} lines={} source={}",
                siblings.isEmpty() ? null : siblings.get(0).getExternalClaimId(),
                siblings.size(), source);
    }
}
