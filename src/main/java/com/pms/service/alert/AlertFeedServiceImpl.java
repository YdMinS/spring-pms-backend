package com.pms.service.alert;

import com.pms.domain.AlertType;
import com.pms.domain.ClaimStatus;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderStatus;
import com.pms.domain.Seller;
import com.pms.dto.response.AlertFeedItemResponse;
import com.pms.dto.response.AlertFeedResponse;
import com.pms.dto.response.NewOrderAlertRow;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link AlertFeedService} 구현 — 세 소스를 커서 하나로 합쳐 최신순 한 장을 만든다(FEATURE_2609_51).
 *
 * <p>🔴 <b>정렬 기준은 하나뿐이다</b>: {@code occurredAt desc · alertType asc(enum 순서) · refId desc}.
 * 쿼리의 {@code order by}·커서 비교·합친 뒤 정렬이 모두 같은 순서여야 한다 — 하나라도 다르면 장 경계에서
 * 행이 새거나 겹친다. 확인·읽음이 없으므로(D2) "미확인 먼저" 같은 축은 <b>존재하지 않는다</b>.
 *
 * <p>🔴 <b>조립 상한(500 같은 숫자)을 두지 않는다</b> — 읽는 양은 {@code 3 × pageSize} 로 이미 고정이다.
 *
 * <p>⚠️ 기간·상한은 {@link AlertWindows} 한 곳에서만 만든다. 요약(배지)도 같은 값을 써야 목록 행 수와
 * 배지 숫자가 일치한다(D3).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlertFeedServiceImpl implements AlertFeedService {

    /** 새 주문 알림의 조건 = 결제완료(쿠팡 ACCEPT). 발주처리되면 PREPARING 이 되어 목록에서 사라진다(D5). */
    private static final OrderStatus NEW_ORDER_STATUS = OrderStatus.PAID;

    /** 사유·본문 미리보기 길이. 목록은 훑어보는 화면이라 한 줄이면 충분하다. */
    private static final int DETAIL_MAX_LENGTH = 200;

    /** 🔴 목록의 유일한 순서. 세 소스 쿼리의 order by 와 같은 규칙이어야 한다. */
    private static final Comparator<AlertFeedItemResponse> FEED_ORDER =
            Comparator.comparing(AlertFeedItemResponse::occurredAt, Comparator.reverseOrder())
                    .thenComparing(AlertFeedItemResponse::alertType)
                    .thenComparing(AlertFeedItemResponse::refId, Comparator.reverseOrder());

    private final OrderLineRepository orderLineRepository;
    private final OrderClaimRepository orderClaimRepository;
    private final CustomerInquiryRepository customerInquiryRepository;
    private final AlertWindows alertWindows;

    @Override
    public AlertFeedResponse feed(AlertType alertType, String rawCursor, int pageSize) {
        Optional<AlertCursor> cursor = AlertCursor.decode(rawCursor);
        // 🔴 첫 장의 상한도 KST 다 — 서버 UTC 로 계산하면 최근 9시간에 들어온 건이 통째로 잘린다(D8).
        LocalDateTime cursorTime = cursor.map(AlertCursor::occurredAt).orElseGet(alertWindows::nowUpperBound);
        Pageable page = PageRequest.of(0, pageSize);

        List<AlertFeedItemResponse> merged = new ArrayList<>();
        // 타입이 지정되면 그 소스만 조회한다 — 버릴 것을 읽지 않는다.
        if (wanted(alertType, AlertType.ORDER)) {
            merged.addAll(newOrders(cursor, cursorTime, page));
        }
        if (wanted(alertType, AlertType.CLAIM)) {
            merged.addAll(claims(cursor, cursorTime, page));
        }
        if (wanted(alertType, AlertType.INQUIRY)) {
            merged.addAll(inquiries(cursor, cursorTime, page));
        }

        // 🔴 커서 걸러내기를 여기서 다시 하지 않는다 — 쿼리가 이미 잘랐다. 두 곳에 두면 규칙이 갈라진다.
        merged.sort(FEED_ORDER);
        boolean hasMore = merged.size() >= pageSize;
        List<AlertFeedItemResponse> items = hasMore ? List.copyOf(merged.subList(0, pageSize)) : List.copyOf(merged);
        String nextCursor = hasMore ? encodeLast(items) : null;
        return new AlertFeedResponse(items, nextCursor);
    }

    private boolean wanted(AlertType requested, AlertType source) {
        return requested == null || requested == source;
    }

    private String encodeLast(List<AlertFeedItemResponse> items) {
        AlertFeedItemResponse last = items.get(items.size() - 1);
        return new AlertCursor(last.occurredAt(), last.alertType(), last.refId()).encode();
    }

    /**
     * 소스별 동률 tie id (D12).
     *
     * <p>순서는 {@code occurredAt desc · alertType asc · refId desc} 하나뿐이다. 커서와 <b>같은 종류</b>면
     * 그 id 부터, 종류 순서상 <b>뒤</b>면 그 시각 전부가 아직 안 보낸 것, <b>앞</b>이면 그 시각은 이미 다 보냈다.
     */
    private Long tieId(Optional<AlertCursor> cursor, AlertType source) {
        if (cursor.isEmpty()) {
            return Long.MAX_VALUE;
        }
        int cmp = source.compareTo(cursor.get().alertType());
        if (cmp == 0) {
            return cursor.get().refId();
        }
        return cmp > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    private List<AlertFeedItemResponse> newOrders(Optional<AlertCursor> cursor, LocalDateTime cursorTime,
                                                  Pageable page) {
        List<NewOrderAlertRow> rows = orderLineRepository.findNewOrderAlerts(
                NEW_ORDER_STATUS, alertWindows.orderFrom(), cursorTime, tieId(cursor, AlertType.ORDER), page);
        return rows.stream().map(this::toItem).toList();
    }

    private List<AlertFeedItemResponse> claims(Optional<AlertCursor> cursor, LocalDateTime cursorTime,
                                               Pageable page) {
        List<OrderClaim> claims = orderClaimRepository.findOpenForAlerts(
                ClaimStatus.closedStatuses(), alertWindows.claimFrom(), cursorTime,
                tieId(cursor, AlertType.CLAIM), page);
        return claims.stream().map(this::toItem).toList();
    }

    private List<AlertFeedItemResponse> inquiries(Optional<AlertCursor> cursor, LocalDateTime cursorTime,
                                                  Pageable page) {
        List<CustomerInquiry> inquiries = customerInquiryRepository.findOpenForAlerts(
                InquiryStatus.openStatuses(), alertWindows.inquiryFrom(), cursorTime,
                tieId(cursor, AlertType.INQUIRY), page);
        return inquiries.stream().map(this::toItem).toList();
    }

    private AlertFeedItemResponse toItem(NewOrderAlertRow row) {
        return new AlertFeedItemResponse(
                AlertType.ORDER,
                row.orderId(),
                row.externalOrderId(),
                (row.platform() != null) ? row.platform().name() : null,
                row.sellerName(),
                row.itemName(),
                (row.itemCount() != null) ? row.itemCount().intValue() : null,
                null,                                   // 주문은 사유·본문이 없다
                row.orderedAt(),
                null);
    }

    private AlertFeedItemResponse toItem(OrderClaim claim) {
        // 🔴 requesterName 을 싣지 않는다 — "처리해야 할 일" 목록에 고객 이름이 필요 없다(2609_23 D13).
        String reason = (claim.getReasonText() != null) ? claim.getReasonText() : claim.getReasonCode();
        return new AlertFeedItemResponse(
                AlertType.CLAIM,
                claim.getId(),
                claim.getExternalOrderId(),
                (claim.getPlatform() != null) ? claim.getPlatform().name() : null,
                sellerName(claim.getMarketplaceAccount()),
                claim.getItemName(),
                null,
                truncate(reason),
                claim.getReceivedAt(),
                claim.getClaimType());
    }

    private AlertFeedItemResponse toItem(CustomerInquiry inquiry) {
        return new AlertFeedItemResponse(
                AlertType.INQUIRY,
                inquiry.getId(),
                inquiry.getExternalOrderId(),
                (inquiry.getPlatform() != null) ? inquiry.getPlatform().name() : null,
                sellerName(inquiry.getMarketplaceAccount()),
                inquiry.getItemName(),
                null,
                truncate(inquiry.getContent()),
                inquiry.getInquiredAt(),
                null);
    }

    /** {@code @EntityGraph} 가 계정·셀러를 즉시 로딩해 둔다 — 없으면 여기서 LazyInitializationException 이다. */
    private String sellerName(MarketplaceAccount account) {
        if (account == null) {
            return null;
        }
        Seller seller = account.getSeller();
        return (seller != null) ? seller.getSellerName() : null;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return (value.length() <= DETAIL_MAX_LENGTH) ? value : value.substring(0, DETAIL_MAX_LENGTH);
    }
}
