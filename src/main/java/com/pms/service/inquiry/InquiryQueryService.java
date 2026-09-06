package com.pms.service.inquiry;

import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.dto.response.InquiryTypeCatalogResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 적재된 customer_inquiry 조회 (GET /api/inquiries — 화면 표시용 read).
 */
public interface InquiryQueryService {

    /**
     * 문의 목록 (최신 문의일순).
     *
     * @param type      null 이면 전체
     * @param status    null 이면 전체
     * @param accountId null 이면 전체. {@code sellerId} 와 <b>함께</b> 올 수 있다(채널이 판매자에 속하므로 AND)
     * @param sellerId  null 이면 전체
     * @param from      문의일 시작(당일 포함). {@code to} 와 <b>함께</b> 주거나 둘 다 null
     * @param to        문의일 끝(<b>당일 포함</b> — 구현이 +1일 00:00 으로 변환)
     * @param keyword   문의 본문·상품명·주문번호 부분일치. null 이면 전체
     * @throws IllegalArgumentException 하나만 주거나 from &gt; to (→ 400)
     *
     * 둘 다 null 이면 기본 창 = 최근 14일.
     */
    List<CustomerInquiryResponse> getInquiries(InquiryType type, InquiryStatus status, Long accountId,
                                               Long sellerId, LocalDate from, LocalDate to, String keyword);

    /**
     * 문의 단건 — 답변 스레드(replied_at ASC) + 관련 주문 + 관련 상품(셀)을 함께 반환한다.
     *
     * @throws com.pms.exception.ResourceNotFoundException 없는 id (→ 404)
     */
    CustomerInquiryResponse getInquiry(Long id);

    /** 활성 계정이 가진 플랫폼들의 지원 문의 유형 (D4). */
    List<InquiryTypeCatalogResponse> getTypes();
}
