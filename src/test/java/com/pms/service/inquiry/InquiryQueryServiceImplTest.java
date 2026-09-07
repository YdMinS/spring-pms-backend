package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * InquiryQueryServiceImpl — 기간 규칙 · relatedOrder 조립 · 404.
 *
 * 컨트롤러 테스트는 이 서비스를 목킹하므로 이 분기는 여기서만 검증된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InquiryQueryServiceImplTest {

    @Mock private CustomerInquiryRepository customerInquiryRepository;
    @Mock private CustomerInquiryReplyRepository customerInquiryReplyRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;

    private InquiryQueryServiceImpl service;

    private final Seller seller = Seller.builder().id(5L).sellerName("테스트셀러").build();
    private final MarketplaceAccount account = MarketplaceAccount.builder()
            .id(7L).platform(Platform.COUPANG).accountAlias("쿠팡-메인").seller(seller).build();

    @BeforeEach
    void setUp() {
        // 정책은 목이 아니라 실제 구현을 넣는다 — 어댑터 목록이 비면 단건 응답의 capability 가
        // "지원하지 않는 채널" 로 내려오는 것까지 함께 고정된다(04).
        service = new InquiryQueryServiceImpl(customerInquiryRepository, customerInquiryReplyRepository,
                orderLineRepository, productListingOptionRepository, marketplaceAccountRepository,
                new InquiryTypeCatalog(), new InquiryReplyPolicy(List.of()));
    }

    @Test
    void getInquiries_halfOpenPeriodOrReversedRange_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.getInquiries(null, null, null, null,
                LocalDate.of(2026, 9, 1), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.getInquiries(null, null, null, null,
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 1), null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(customerInquiryRepository);
    }

    @Test
    void getInquiries_noPeriod_usesLastFourteenDays() {
        given(customerInquiryRepository.search(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of());

        service.getInquiries(null, null, null, null, null, null, "  ");

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(customerInquiryRepository).search(any(), any(), any(), any(),
                start.capture(), end.capture(), keyword.capture());
        assertThat(start.getValue()).isEqualTo(LocalDate.now().minusDays(14).atStartOfDay());
        // 상한은 배타적 — 오늘 들어온 문의가 빠지지 않는다.
        assertThat(end.getValue()).isEqualTo(LocalDate.now().plusDays(1).atStartOfDay());
        assertThat(keyword.getValue()).isNull();          // 공백 키워드는 필터가 아니다
    }

    @Test
    void getInquiry_linkedOrder_returnsEveryLineOfThatOrderAndFlagsTheInquiryLine() {
        OrderLine inquiryLine = orderLine(11L, "양말", OrderStatus.PREPARING);
        OrderLine siblingLine = orderLine(12L, "장갑", OrderStatus.PREPARING);
        CustomerInquiry inquiry = inquiry(inquiryLine);
        given(customerInquiryRepository.findWithAccountById(1L)).willReturn(Optional.of(inquiry));
        given(customerInquiryReplyRepository.findByInquiry_IdOrderByRepliedAtAsc(1L)).willReturn(List.of());
        given(orderLineRepository.findByExternalOrderId("O-1")).willReturn(List.of(inquiryLine, siblingLine));

        CustomerInquiryResponse response = service.getInquiry(1L);

        assertThat(response.linked()).isTrue();
        assertThat(response.relatedOrder().externalOrderId()).isEqualTo("O-1");
        assertThat(response.relatedOrder().ordererName()).isEqualTo("홍길동");
        // 합포장 형제 라인까지 전부 담고, 문의가 걸린 라인만 표시된다.
        assertThat(response.relatedOrder().lines()).hasSize(2);
        assertThat(response.relatedOrder().lines().get(0).orderItemId()).isEqualTo(11L);
        // 상태는 중립 값이다(2609_26 D4) — 원문(INSTRUCT)이 아니라 PREPARING 이 내려간다.
        assertThat(response.relatedOrder().lines().get(0).status()).isEqualTo("PREPARING");
        assertThat(response.relatedOrder().lines().get(0).isInquiryLine()).isTrue();
        assertThat(response.relatedOrder().lines().get(1).isInquiryLine()).isFalse();
        assertThat(response.accountAlias()).isEqualTo("쿠팡-메인");
        assertThat(response.sellerName()).isEqualTo("테스트셀러");
    }

    @Test
    void getInquiry_withoutOrderLink_hasNullRelatedOrder() {
        CustomerInquiry inquiry = inquiry(null);
        given(customerInquiryRepository.findWithAccountById(1L)).willReturn(Optional.of(inquiry));
        given(customerInquiryReplyRepository.findByInquiry_IdOrderByRepliedAtAsc(1L)).willReturn(List.of());

        CustomerInquiryResponse response = service.getInquiry(1L);

        assertThat(response.relatedOrder()).isNull();
        assertThat(response.relatedListing()).isNull();
        assertThat(response.linked()).isFalse();
        assertThat(response.replies()).isEmpty();
    }

    @Test
    void getInquiry_unknownId_throwsNotFound() {
        given(customerInquiryRepository.findWithAccountById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInquiry(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private OrderLine orderLine(Long id, String itemName, OrderStatus status) {
        Order order = Order.builder()
                .id(100L).marketplaceAccount(account)
                .externalOrderId("O-1")
                .ordererName("홍길동").receiverName("홍길순")
                .orderedAt(LocalDateTime.of(2026, 9, 1, 9, 0))
                .build();
        return OrderLine.builder()
                .id(id).order(order).itemName(itemName)
                .orderQty(2).cancelQty(0).holdQty(0).status(status)
                .build();
    }

    private CustomerInquiry inquiry(OrderLine orderLine) {
        return CustomerInquiry.builder()
                .id(1L)
                .marketplaceAccount(account)
                .platform(Platform.COUPANG)
                .inquiryType(InquiryType.PRODUCT_QNA)
                .externalInquiryId("I-1")
                .externalItemId("V-11")
                .externalOrderId(orderLine != null ? "O-1" : null)
                .orderLine(orderLine)
                .content("문의 본문")
                .status(InquiryStatus.UNANSWERED)
                .platformStatus("NOANSWER")
                .inquiredAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .lastSyncedAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
    }
}
