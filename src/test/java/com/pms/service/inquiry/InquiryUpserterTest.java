package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.domain.Platform;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * InquiryUpserter — 멱등 upsert · 주문 매칭(D14·D15) · 로컬 임시 답변 정리(04 Step 4).
 *
 * 주문 매칭 실패는 <b>정상 경로</b>다: 상품문의는 구매 전 질문이 다수라 미연결이 기본이고,
 * 그때도 문의 자체는 반드시 저장돼야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InquiryUpserterTest {

    private static final LocalDateTime INQUIRED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);

    @Mock private CustomerInquiryRepository customerInquiryRepository;
    @Mock private CustomerInquiryReplyRepository customerInquiryReplyRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @InjectMocks private InquiryUpserter upserter;

    private final MarketplaceAccount account = MarketplaceAccount.builder()
            .id(7L).platform(Platform.COUPANG).vendorId("A0001").build();

    @Test
    void upsert_newInquiry_insertsWithMatchedOrderLine() {
        OrderItem line = OrderItem.builder().id(11L).externalOrderId("O-1").externalItemId("V-1").build();
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(
                7L, "O-1", "V-1")).willReturn(List.of(line));
        given(customerInquiryRepository.findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(
                any(), any(), any())).willReturn(Optional.empty());

        upserter.upsert(account, record(InquiryStatus.UNANSWERED, "O-1", List.of()));

        ArgumentCaptor<CustomerInquiry> saved = ArgumentCaptor.forClass(CustomerInquiry.class);
        verify(customerInquiryRepository).save(saved.capture());
        assertThat(saved.getValue().getExternalInquiryId()).isEqualTo("I-1");
        assertThat(saved.getValue().getInquiryType()).isEqualTo(InquiryType.PRODUCT_QNA);
        assertThat(saved.getValue().getOrderItem()).isEqualTo(line);
        assertThat(saved.getValue().isLinked()).isTrue();
    }

    @Test
    void upsert_unchangedInquiry_doesNotSave() {
        // 재적재는 멱등이다 — 값이 하나도 안 바뀌면 UPDATE 를 쏘지 않는다(modified_date 오염 방지).
        CustomerInquiry existing = existing(InquiryStatus.UNANSWERED, null, null);
        given(customerInquiryRepository.findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(
                7L, InquiryType.PRODUCT_QNA, "I-1")).willReturn(Optional.of(existing));

        upserter.upsert(account, record(InquiryStatus.UNANSWERED, null, List.of()));

        verify(customerInquiryRepository, never()).save(any());
    }

    @Test
    void upsert_withoutOrderMatch_stillStoresInquiryUnlinked() {
        given(customerInquiryRepository.findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(
                any(), any(), any())).willReturn(Optional.empty());
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(
                7L, "O-9", "V-1")).willReturn(List.of());

        upserter.upsert(account, record(InquiryStatus.UNANSWERED, "O-9", List.of()));

        ArgumentCaptor<CustomerInquiry> saved = ArgumentCaptor.forClass(CustomerInquiry.class);
        verify(customerInquiryRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderItem()).isNull();
        assertThat(saved.getValue().isLinked()).isFalse();
        assertThat(saved.getValue().getExternalOrderId()).isEqualTo("O-9");   // 주문번호 자체는 보존
    }

    @Test
    void upsert_ambiguousOrderMatch_leavesOrderItemNull() {
        // 합포장으로 3키가 2건이면 틀린 라인에 붙이느니 미연결로 둔다.
        given(customerInquiryRepository.findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(
                any(), any(), any())).willReturn(Optional.empty());
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(
                7L, "O-1", "V-1")).willReturn(List.of(
                        OrderItem.builder().id(11L).build(), OrderItem.builder().id(12L).build()));

        upserter.upsert(account, record(InquiryStatus.UNANSWERED, "O-1", List.of()));

        ArgumentCaptor<CustomerInquiry> saved = ArgumentCaptor.forClass(CustomerInquiry.class);
        verify(customerInquiryRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderItem()).isNull();
    }

    @Test
    void syncReplies_removesOnlyLocalReplyWithSameContent() {
        // 04 가 전송 직후 넣는 로컬 임시 행: 내용이 같은 것만 지운다(두 줄로 보이는 것을 막는다).
        // 내용이 다른 로컬 행은 남긴다 — 전송은 됐는데 아직 안 실려온 건일 수 있다.
        CustomerInquiry existing = existing(InquiryStatus.UNANSWERED, null, null);
        given(customerInquiryRepository.findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(
                any(), any(), any())).willReturn(Optional.of(existing));
        given(customerInquiryRepository.save(any())).willAnswer(call -> call.getArgument(0));

        CustomerInquiryReply sameContent = localReply(101L, " 내일 출고 예정입니다. ");
        CustomerInquiryReply otherContent = localReply(102L, "아직 안 실려온 다른 답변");
        given(customerInquiryReplyRepository.findByInquiry_IdOrderByRepliedAtAsc(3L))
                .willReturn(List.of(sameContent, otherContent));

        InquiryRecord.ReplyRecord incoming = new InquiryRecord.ReplyRecord(
                "5001", null, InquiryAuthorRole.SELLER, null, "내일 출고 예정입니다.", null,
                LocalDateTime.of(2026, 9, 1, 11, 0));
        upserter.upsert(account, record(InquiryStatus.ANSWERED, null, List.of(incoming)));

        ArgumentCaptor<List<CustomerInquiryReply>> deleted = ArgumentCaptor.forClass(List.class);
        verify(customerInquiryReplyRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(sameContent);
    }

    private CustomerInquiryReply localReply(Long id, String content) {
        return CustomerInquiryReply.builder()
                .id(id)
                .externalReplyId("local-" + id)
                .authorRole(InquiryAuthorRole.SELLER)
                .content(content)
                .build();
    }

    private CustomerInquiry existing(InquiryStatus status, String externalOrderId, OrderItem orderItem) {
        return CustomerInquiry.builder()
                .id(3L)
                .marketplaceAccount(account)
                .platform(Platform.COUPANG)
                .inquiryType(InquiryType.PRODUCT_QNA)
                .externalInquiryId("I-1")
                .externalItemId("V-1")
                .externalOrderId(externalOrderId)
                .orderItem(orderItem)
                .content("재입고 예정이 있나요?")
                .status(status)
                .platformStatus(status == InquiryStatus.ANSWERED ? "ANSWERED" : "NOANSWER")
                .inquiredAt(INQUIRED_AT)
                .lastSyncedAt(INQUIRED_AT)
                .build();
    }

    private InquiryRecord record(InquiryStatus status, String externalOrderId,
                                 List<InquiryRecord.ReplyRecord> replies) {
        return new InquiryRecord(
                InquiryType.PRODUCT_QNA, "I-1", "V-1", externalOrderId, null, null,
                "재입고 예정이 있나요?", null, status,
                status == InquiryStatus.ANSWERED ? "ANSWERED" : "NOANSWER",
                INQUIRED_AT, null, replies);
    }
}
