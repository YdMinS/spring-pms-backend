package com.pms.service.claim;

import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.Seller;
import com.pms.dto.response.OrderClaimResponse;
import com.pms.repository.OrderClaimRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ClaimQueryServiceImpl — 기간 검증, 기본 창, 조회 후 필터(status·keyword).
 * 컨트롤러 테스트는 이 서비스를 목킹하므로, 이 분기는 여기서만 검증된다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimQueryServiceImplTest {

    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private CoupangProperties coupangProperties;
    @InjectMocks private ClaimQueryServiceImpl service;

    @Test
    void getClaims_halfOpenPeriodOrReversedRange_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.getClaims(ClaimType.RETURN, null, null,
                LocalDate.of(2026, 9, 1), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.getClaims(ClaimType.RETURN, null, null,
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 1), null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(orderClaimRepository);
    }

    @Test
    void getClaims_noPeriod_usesSyncDaysWindow() {
        given(coupangProperties.getSyncDays()).willReturn(14);
        given(orderClaimRepository.findInPeriod(eq(ClaimType.RETURN), any(), any())).willReturn(List.of());

        service.getClaims(null, null, null, null, null, null);

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderClaimRepository).findInPeriod(eq(ClaimType.RETURN), start.capture(), any());
        // 기본 창은 주문 화면과 같은 값을 읽어야 한다 — 기대치도 properties 로 만든다(숫자를 박지 않는다)
        assertThat(start.getValue())
                .isEqualTo(LocalDate.now().minusDays(coupangProperties.getSyncDays()).atStartOfDay());
    }

    @Test
    void getClaims_keywordAndStatus_filterAfterQuery() {
        given(orderClaimRepository.findInPeriod(eq(ClaimType.RETURN), any(), any()))
                .willReturn(List.of(
                        claim(1L, ClaimStatus.RECEIVED, "O-100", "홍길동", "양말"),
                        claim(2L, ClaimStatus.RECEIVED, "O-200", "김철수", "장갑"),
                        claim(3L, ClaimStatus.DONE, "O-300", "이영희", "모자")));

        List<OrderClaimResponse> byKeyword = service.getClaims(ClaimType.RETURN, null, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), "철수");
        assertThat(byKeyword).extracting(OrderClaimResponse::id).containsExactly(2L);

        List<OrderClaimResponse> byStatus = service.getClaims(ClaimType.RETURN, ClaimStatus.DONE, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), null);
        assertThat(byStatus).extracting(OrderClaimResponse::id).containsExactly(3L);
        assertThat(byStatus.get(0).sellerName()).isEqualTo("테스트셀러");
        assertThat(byStatus.get(0).linked()).isFalse();
    }

    @Test
    void getClaims_exchangeWithReshipInvoice_exposesBothInvoicePairs() {
        // 07·08 이 이 두 필드를 읽는다 — 빠지면 화면이 조용히 빈칸을 그린다.
        given(orderClaimRepository.findInPeriod(eq(ClaimType.EXCHANGE), any(), any()))
                .willReturn(List.of(exchangeClaim()));

        List<OrderClaimResponse> claims = service.getClaims(ClaimType.EXCHANGE, null, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), null);

        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).claimType()).isEqualTo(ClaimType.EXCHANGE);
        assertThat(claims.get(0).collectInvoiceNo()).isEqualTo("COL-1");
        assertThat(claims.get(0).reshipInvoiceNo()).isEqualTo("RES-1");
        assertThat(claims.get(0).reshipCarrierCode()).isEqualTo("HANJIN");
    }

    private OrderClaim exchangeClaim() {
        return claim(9L, ClaimStatus.IN_PROGRESS, "O-900", "홍길동", "양말").toBuilder()
                .claimType(ClaimType.EXCHANGE).platformStatus("PROGRESS")
                .collectInvoiceNo("COL-1").collectCarrierCode("CJGLS")
                .reshipInvoiceNo("RES-1").reshipCarrierCode("HANJIN")
                .build();
    }

    private OrderClaim claim(Long id, ClaimStatus status, String orderId, String requester, String itemName) {
        Seller seller = Seller.builder().id(5L).sellerName("테스트셀러").build();
        MarketplaceAccount account = MarketplaceAccount.builder()
                .id(1L).seller(seller).platform("COUPANG").build();
        return OrderClaim.builder()
                .id(id).marketplaceAccount(account).platform("COUPANG").claimType(ClaimType.RETURN)
                .externalClaimId("R-" + id).externalOrderId(orderId).externalItemId("V-" + id)
                .orderItemMatchAttempts(0).itemName(itemName).quantity(1)
                .status(status).platformStatus("UC").requesterName(requester)
                .receivedAt(LocalDateTime.of(2026, 9, 2, 9, 0))
                .syncedAt(LocalDateTime.of(2026, 9, 2, 9, 30))
                .build();
    }
}
