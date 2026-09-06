package com.pms.repository;

import com.pms.domain.CustomerInquiryReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * customer_inquiry_reply 접근 (FEATURE_2609_23).
 *
 * ⚠️ 답변의 읽기·쓰기는 <b>전부 여기</b>를 지난다. {@code CustomerInquiry.replies} 컬렉션은 상세 화면의
 * 연관 선언일 뿐이고, 쓰기 경로가 그 컬렉션을 초기화하면 헤더를 {@code toBuilder()} 로 재조립해 저장할 때
 * orphanRemoval 컬렉션이 교체된 것으로 취급될 수 있다.
 */
public interface CustomerInquiryReplyRepository extends JpaRepository<CustomerInquiryReply, Long> {

    /** 한 문의의 답변 스레드 — 시간순(상세 표시 순서 그대로). */
    List<CustomerInquiryReply> findByInquiry_IdOrderByRepliedAtAsc(Long inquiryId);
}
