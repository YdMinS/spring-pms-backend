package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.dto.response.CustomerInquiryReplyResponse;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.dto.response.InquiryRelatedListingResponse;
import com.pms.dto.response.InquiryRelatedOrderResponse;
import com.pms.dto.response.InquiryTypeCatalogResponse;
import com.pms.dto.response.ReplyCapability;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * {@link InquiryQueryService} 구현. 엔티티 → {@link CustomerInquiryResponse} 매핑 (FEATURE_2609_23).
 *
 * 필터 6개는 리포지토리의 <b>nullable 파라미터 쿼리 1개</b>가 전부 처리한다 — 조합별 명명 메서드를
 * 만들면 조합이 폭발한다(2609_18 이 필터 3개로 버틴 방식은 여기서 성립하지 않는다).
 *
 * <p>단건은 우측 패널까지 한 번에 실어 보낸다(PLAN §6) — 프론트가 {@code externalOrderId} 로 주문
 * 목록을 다시 뒤지면 기간 필터에 걸려 못 찾는 건이 생긴다.
 *
 * <p>단건에는 {@code replyCapability}(D5)도 함께 싣는다 — 목록에는 넣지 않는다(행마다 계정을 들여다봐야
 * 하고 목록에서는 쓰지 않는다). 답변 전송({@code InquiryReplyService})도 <b>이 단건 조회를 그대로 호출</b>해
 * 응답을 조립하므로 조립 경로가 한 벌뿐이다.
 *
 * <p>🔴 연락처·주소를 응답에 담지 않는다(D13). 이름만 실린다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InquiryQueryServiceImpl implements InquiryQueryService {

    /** 기간 미지정 시 기본 창(일). 주문·클레임 화면과 같은 폭이다. */
    private static final int DEFAULT_WINDOW_DAYS = 14;

    private final CustomerInquiryRepository customerInquiryRepository;
    private final CustomerInquiryReplyRepository customerInquiryReplyRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final InquiryTypeCatalog inquiryTypeCatalog;
    private final InquiryReplyPolicy inquiryReplyPolicy;

    @Override
    public List<CustomerInquiryResponse> getInquiries(InquiryType type, InquiryStatus status, Long accountId,
                                                      Long sellerId, LocalDate from, LocalDate to, String keyword) {
        LocalDateTime start;
        LocalDateTime endExclusive;
        if (from == null && to == null) {
            start = LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS).atStartOfDay();
            endExclusive = LocalDate.now().plusDays(1).atStartOfDay();
        } else {
            if (from == null || to == null) {
                throw new IllegalArgumentException("조회 기간은 from 과 to 를 함께 지정해야 합니다.");
            }
            if (from.isAfter(to)) {
                throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
            }
            start = from.atStartOfDay();
            // 상한은 배타적 — to 당일 마지막 초에 들어온 문의를 놓치지 않는다.
            endExclusive = to.plusDays(1).atStartOfDay();
        }

        String needle = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return customerInquiryRepository
                .search(type, status, accountId, sellerId, start, endExclusive, needle).stream()
                .map(this::toListResponse)
                .toList();
    }

    @Override
    public CustomerInquiryResponse getInquiry(Long id) {
        CustomerInquiry inquiry = customerInquiryRepository.findWithAccountById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inquiry", id));

        List<CustomerInquiryReply> thread =
                customerInquiryReplyRepository.findByInquiry_IdOrderByRepliedAtAsc(inquiry.getId());
        List<CustomerInquiryReplyResponse> replies = thread.stream()
                .map(this::toReplyResponse)
                .toList();

        // 답변 가능 여부는 단건에서만 채운다(D5) — 판정은 정책 한 곳이고, 답변 전송 응답도 같은 경로를 탄다.
        ReplyCapability capability = inquiryReplyPolicy.evaluate(inquiry, thread);
        return toResponse(inquiry, replies, relatedOrder(inquiry), relatedListing(inquiry), capability);
    }

    @Override
    public List<InquiryTypeCatalogResponse> getTypes() {
        List<Platform> platforms = marketplaceAccountRepository.findByIsActiveTrue().stream()
                .map(MarketplaceAccount::getPlatform)
                .toList();
        return inquiryTypeCatalog.forPlatforms(platforms);
    }

    /**
     * 관련 주문 = 같은 {@code external_order_id} 의 <b>모든</b> 라인(합포장 포함).
     * 문의에 주문 라인 연결이 없으면 null 이다 — 주문번호만으로 열지 않는다(연결 판정의 소유자는 적재다).
     */
    private InquiryRelatedOrderResponse relatedOrder(CustomerInquiry inquiry) {
        OrderItem linked = inquiry.getOrderItem();
        if (linked == null) {
            return null;
        }
        Long accountId = inquiry.getMarketplaceAccount().getId();
        List<OrderItem> lines = orderItemRepository.findByExternalOrderId(linked.getExternalOrderId()).stream()
                .filter(line -> line.getMarketplaceAccount() != null
                        && accountId.equals(line.getMarketplaceAccount().getId()))
                .sorted(Comparator.comparing(OrderItem::getId))
                .toList();
        if (lines.isEmpty()) {
            lines = List.of(linked);
        }

        OrderItem head = lines.get(0);
        return new InquiryRelatedOrderResponse(
                head.getExternalOrderId(),
                head.getPaidAt(),
                head.getOrdererName(),          // 2609_06 범위 — 이름만
                head.getReceiverName(),
                lines.stream()
                        .map(line -> new InquiryRelatedOrderResponse.Line(
                                line.getId(),
                                line.getItemName(),
                                line.getOrderCount() == null ? 0 : line.getOrderCount(),
                                line.getCancelCount() == null ? 0 : line.getCancelCount(),
                                line.effectiveStatus(),
                                line.getId().equals(linked.getId())))
                        .toList());
    }

    /** 관련 상품(셀) — D15 의 연결 결과. 옵션명은 vendorItemId 로 옵션을 되짚어 얻는다. */
    private InquiryRelatedListingResponse relatedListing(CustomerInquiry inquiry) {
        ProductListing listing = inquiry.getProductListing();
        if (listing == null) {
            return null;
        }
        String optionName = (inquiry.getExternalItemId() == null) ? null
                : productListingOptionRepository.findByPlatformOptionId(inquiry.getExternalItemId())
                        .map(option -> option.getOptionName())
                        .orElse(null);
        return new InquiryRelatedListingResponse(listing.getId(), listing.getName(), optionName);
    }

    /** 목록 응답 — 스레드·우측 패널은 담지 않는다(PLAN §3). */
    private CustomerInquiryResponse toListResponse(CustomerInquiry inquiry) {
        return toResponse(inquiry, null, null, null, null);
    }

    private CustomerInquiryResponse toResponse(CustomerInquiry inquiry,
                                               List<CustomerInquiryReplyResponse> replies,
                                               InquiryRelatedOrderResponse relatedOrder,
                                               InquiryRelatedListingResponse relatedListing,
                                               ReplyCapability replyCapability) {
        MarketplaceAccount account = inquiry.getMarketplaceAccount();
        Seller seller = (account != null) ? account.getSeller() : null;
        return new CustomerInquiryResponse(
                inquiry.getId(),
                inquiry.getPlatform().name(),
                (account != null) ? account.getId() : null,
                (account != null) ? account.getAccountAlias() : null,
                (seller != null) ? seller.getId() : null,
                (seller != null) ? seller.getSellerName() : null,
                inquiry.getInquiryType(),
                inquiry.getStatus(),
                inquiry.getPlatformStatus(),
                inquiry.getExternalInquiryId(),
                inquiry.getExternalOrderId(),
                inquiry.getExternalItemId(),
                inquiry.getExternalProductId(),
                (inquiry.getProductListing() != null) ? inquiry.getProductListing().getId() : null,
                (inquiry.getOrderItem() != null) ? inquiry.getOrderItem().getId() : null,
                inquiry.getItemName(),
                inquiry.getContent(),
                inquiry.getCategory(),
                inquiry.getInquiredAt(),
                inquiry.getAnsweredAt(),
                inquiry.isLinked(),
                replies,
                relatedOrder,
                relatedListing,
                replyCapability);
    }

    private CustomerInquiryReplyResponse toReplyResponse(CustomerInquiryReply reply) {
        return new CustomerInquiryReplyResponse(
                reply.getId(),
                reply.getExternalReplyId(),
                reply.getParentExternalReplyId(),
                reply.getAuthorRole(),
                reply.getAuthorName(),
                reply.getContent(),
                reply.getTransferStatus(),
                reply.getRepliedAt());
    }
}
