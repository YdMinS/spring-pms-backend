package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.dto.request.InquiryReplyRequest;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.dto.response.ReplyCapability;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link InquiryReplyService} 구현 — 선점 → 재판정 → 전송 → 로컬 반영.
 *
 * <p>🔴 <b>클래스·메서드 어디에도 {@code @Transactional} 을 붙이지 않는다</b> — 외부 HTTP 가
 * 트랜잭션을 물고 늘어진다({@code ClaimActionServiceImpl} 과 같은 이유). 로컬 반영만
 * {@link InquiryReplyRecorder}(REQUIRES_NEW)가 짧게 커밋한다.
 *
 * <p>🔴 <b>{@code "COUPANG"} 같은 플랫폼 문자열이 이 클래스에 없어야 한다</b> — 어댑터를
 * {@code platform()} 으로 고르는 것이 플랫폼 지식의 전부다(D19).
 *
 * <p>⚠️ 화면이 버튼을 잠갔다는 것을 신뢰하지 않는다 — {@link InquiryReplyPolicy} 를 전송 직전에 다시 본다.
 * {@code parentReplyId} 도 <b>정책이 고른 값</b>만 쓴다(클라이언트가 보낸 값은 받지도 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryReplyServiceImpl implements InquiryReplyService {

    /**
     * 같은 문의로 동시에 들어온 두 번째 요청을 막는 프로세스 내 선점 집합.
     *
     * 1차 방어는 화면의 버튼 잠금, 이것이 2차, <b>최종 방어는 쿠팡의 중복 답변 400</b> 이다 —
     * 다중 인스턴스까지 막으려고 DB 락·분산 락을 만들지 말 것(되돌릴 수 없는 쓰기라 마지막 방어가 이미 있다).
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private final InquiryReplyPolicy inquiryReplyPolicy;
    private final InquiryReplyRecorder inquiryReplyRecorder;
    private final InquiryQueryService inquiryQueryService;
    private final CustomerInquiryRepository customerInquiryRepository;
    private final CustomerInquiryReplyRepository customerInquiryReplyRepository;

    @Override
    public CustomerInquiryResponse reply(Long inquiryId, InquiryReplyRequest request) {
        CustomerInquiry inquiry = customerInquiryRepository.findWithAccountById(inquiryId)
                .orElseThrow(() -> new ResourceNotFoundException("Inquiry", inquiryId));

        if (!inFlight.add(inquiryId)) {
            throw new IllegalArgumentException("이미 전송 중인 문의입니다.");
        }
        try {
            List<CustomerInquiryReply> replies =
                    customerInquiryReplyRepository.findByInquiry_IdOrderByRepliedAtAsc(inquiryId);
            ReplyCapability capability = inquiryReplyPolicy.evaluate(inquiry, replies);
            if (!capability.canReply()) {
                throw new IllegalArgumentException(capability.reason());
            }

            // Check, send and store all use the same stripped value (the 11st non-delivery form stores it inside its
            // record line) — no duplicate reply that differs only in leading / trailing spaces.
            String body = (request.content() == null) ? "" : request.content().strip();
            if (body.length() < capability.minLength() || body.length() > capability.maxLength()) {
                throw new IllegalArgumentException(String.format("답변은 %d자 이상 %d자 이하로 입력해 주세요.",
                        capability.minLength(), capability.maxLength()));
            }

            InquiryReplyAdapter adapter = inquiryReplyPolicy.resolve(inquiry.getPlatform())
                    // 정책 1순위에서 이미 걸러진다 — 여기 도달하면 정책과 어댑터 목록이 어긋난 것이다.
                    .orElseThrow(() -> new IllegalArgumentException("이 채널은 아직 답변 전송을 지원하지 않습니다."));

            // FEATURE_2610_17 / D68: the non-delivery form also sends a reason (and a re-shipping invoice) and
            // records 「{reason name} — {detail}」 instead of the body alone.
            InquiryReplyPolicy.NonDeliveryInput nonDelivery = ReplyCapability.FORM_NON_DELIVERY.equals(capability.form())
                    ? inquiryReplyPolicy.nonDelivery(inquiry, body, request.reasonCode(), request.carrierCode(),
                            request.invoiceNumber())
                    : null;
            InquiryReplyAdapter.ReplyCommand command = (nonDelivery == null)
                    ? new InquiryReplyAdapter.ReplyCommand(body, capability.parentReplyId())
                    : new InquiryReplyAdapter.ReplyCommand(body, null, nonDelivery.reasonCode(),
                            nonDelivery.carrierCode(), nonDelivery.invoiceNumber());
            send(adapter, inquiry, command);
            inquiryReplyRecorder.record(inquiryId, nonDelivery == null ? body : nonDelivery.recordText());
            log.info("고객문의 답변 전송 성공: inquiry={} type={} account={} length={}",
                    inquiryId, inquiry.getInquiryType(), inquiry.getMarketplaceAccount().getId(), body.length());
        } finally {
            inFlight.remove(inquiryId);
        }

        // 🔴 상세 조회와 같은 경로로 다시 조립한다 — 전용 응답 타입을 만들면 프론트가 매핑을 두 번 짠다.
        //    로컬 반영 뒤의 값이라 replyCapability 는 canReply=false 로 다시 채워진다(D17).
        return inquiryQueryService.getInquiry(inquiryId);
    }

    /**
     * Send. Failures go up <b>split</b> two ways.
     *
     * <ul>
     *   <li>{@link InquiryReplyFailedException} = the marketplace received it and refused → 400 (fix the body and
     *       retry)</li>
     *   <li>any other exception (timeout · network) = <b>success unknown</b> → 502. Local data is untouched — a resend
     *       could duplicate the reply, so the user is told to check the next sync</li>
     * </ul>
     */
    private void send(InquiryReplyAdapter adapter, CustomerInquiry inquiry, InquiryReplyAdapter.ReplyCommand command) {
        try {
            adapter.reply(inquiry.getMarketplaceAccount(), inquiry, command);
        } catch (InquiryReplyFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("고객문의 답변 전송 결과 불명: inquiry={} type={}", inquiry.getId(), inquiry.getInquiryType(), e);
            throw new BusinessException(
                    "전송 결과를 확인하지 못했습니다. 다음 동기화 후 답변 여부를 확인해 주세요.",
                    HttpStatus.BAD_GATEWAY);
        }
    }
}
