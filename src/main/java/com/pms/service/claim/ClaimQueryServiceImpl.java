package com.pms.service.claim;

import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.OrderClaim;
import com.pms.dto.response.OrderClaimResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.OrderClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * {@link ClaimQueryService} 구현. 엔티티 → {@link OrderClaimResponse} 매핑(PII 는 이름만 노출).
 *
 * 기간 미지정 시 기본 창 = 주문 조회와 같은 {@code coupang.sync-days} — 두 화면의 기본 창이 갈라지지
 * 않게 같은 값을 읽는다. 적재 창({@code cancel-sync-days})이 더 좁으므로 기본 창의 앞부분이 비는 것은
 * 정상이며, 추적(05)이 {@code lastClaimSyncAt} 으로 적재 창을 넓히면 채워진다.
 *
 * {@code status}·{@code keyword} 는 <b>조회 후 필터</b>다 — 건수가 작고 인덱스는 receivedAt 창이 먹는다
 * (FEATURE_2609_08 의 판단과 동일).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClaimQueryServiceImpl implements ClaimQueryService {

    private final OrderClaimRepository orderClaimRepository;
    private final CoupangProperties coupangProperties;

    @Override
    public List<OrderClaimResponse> getClaims(ClaimType type, ClaimStatus status, Long sellerId,
                                              LocalDate from, LocalDate to, String keyword) {
        ClaimType claimType = (type != null) ? type : ClaimType.RETURN;
        LocalDateTime start;
        LocalDateTime endExclusive;

        if (from == null && to == null) {
            start = LocalDate.now().minusDays(coupangProperties.getSyncDays()).atStartOfDay();
            endExclusive = LocalDate.now().plusDays(1).atStartOfDay();
        } else {
            if (from == null || to == null) {
                throw new IllegalArgumentException("조회 기간은 from 과 to 를 함께 지정해야 합니다.");
            }
            if (from.isAfter(to)) {
                throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
            }
            start = from.atStartOfDay();
            // 상한은 배타적 — to 당일 마지막 초에 접수된 건을 놓치지 않는다.
            endExclusive = to.plusDays(1).atStartOfDay();
        }

        List<OrderClaim> claims = (sellerId == null)
                ? orderClaimRepository.findInPeriod(claimType, start, endExclusive)
                : orderClaimRepository.findInPeriodBySeller(claimType, sellerId, start, endExclusive);

        return claims.stream()
                .filter(claim -> status == null || claim.getStatus() == status)
                .filter(claim -> matchesKeyword(claim, keyword))
                .map(this::toResponse)
                .toList();
    }

    @Override
    public OrderClaimResponse getClaim(Long id) {
        return orderClaimRepository.findWithAccountById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", id));
    }

    /** 주문번호·신청자명·상품명 부분일치(대소문자 무시). keyword 가 비어 있으면 전체 통과. */
    private boolean matchesKeyword(OrderClaim claim, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(claim.getExternalOrderId(), needle)
                || contains(claim.getRequesterName(), needle)
                || contains(claim.getItemName(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private OrderClaimResponse toResponse(OrderClaim claim) {
        var seller = claim.getMarketplaceAccount().getSeller();
        return new OrderClaimResponse(
                claim.getId(),
                claim.getPlatform(),
                claim.getClaimType(),
                claim.getStatus(),
                claim.getPlatformStatus(),
                claim.getExternalClaimId(),
                claim.getExternalOrderId(),
                claim.getItemName(),
                claim.getQuantity(),
                claim.getReasonCode(),
                claim.getReasonText(),
                claim.getFaultType(),
                claim.getReturnShippingCharge(),
                claim.getCollectInvoiceNo(),
                claim.getCollectCarrierCode(),
                claim.getReshipInvoiceNo(),
                claim.getReshipCarrierCode(),
                claim.getRequesterName(),
                claim.getReceivedAt(),
                (seller != null) ? seller.getId() : null,
                (seller != null) ? seller.getSellerName() : null,
                (claim.getOrderItem() != null) ? claim.getOrderItem().getId() : null,
                claim.isLinked());
    }
}
