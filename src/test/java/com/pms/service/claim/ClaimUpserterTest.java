package com.pms.service.claim;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ClaimUpserter — 2단 주문 매칭(D22)과 멱등 upsert.
 * 값이 안 바뀌면 save 를 호출하지 않는 것이 마지막 케이스의 요지다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimUpserterTest {

    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @InjectMocks private ClaimUpserter upserter;

    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        account = MarketplaceAccount.builder().id(1L).platform("COUPANG").vendorId("V0001").build();
    }

    @Test
    void upsert_newClaimWithBoxId_linksByFourKeyMatch() {
        OrderItem line = orderLine(10L);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndExternalClaimIdAndExternalItemId(
                1L, "R-1", "V-1")).willReturn(Optional.empty());
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                1L, "B-1", "O-1", "V-1")).willReturn(Optional.of(line));

        upserter.upsert(account, ClaimType.RETURN, record("B-1", ClaimStatus.RECEIVED, "UC", 2));

        ArgumentCaptor<OrderClaim> captor = ArgumentCaptor.forClass(OrderClaim.class);
        verify(orderClaimRepository, times(1)).save(captor.capture());
        OrderClaim saved = captor.getValue();
        assertThat(saved.getOrderItem()).isSameAs(line);
        assertThat(saved.getPlatform()).isEqualTo("COUPANG");
        assertThat(saved.getClaimType()).isEqualTo(ClaimType.RETURN);
        assertThat(saved.getOrderItemMatchAttempts()).isZero();
        assertThat(saved.getSyncedAt()).isNotNull();
        assertThat(saved.getTenantId()).isNull();   // @TenantId 가 INSERT 시 주입한다
        // boxId 가 있으면 3키 폴백은 타지 않는다
        verify(orderItemRepository, never())
                .findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(anyLong(), anyString(), anyString());
    }

    @Test
    void upsert_nullBoxId_fallsBackToThreeKeyMatch() {
        OrderItem line = orderLine(11L);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndExternalClaimIdAndExternalItemId(
                1L, "R-1", "V-1")).willReturn(Optional.empty());
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(
                1L, "O-1", "V-1")).willReturn(List.of(line));

        upserter.upsert(account, ClaimType.RETURN, record(null, ClaimStatus.RECEIVED, "UC", 1));

        ArgumentCaptor<OrderClaim> captor = ArgumentCaptor.forClass(OrderClaim.class);
        verify(orderClaimRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderItem()).isSameAs(line);
        assertThat(captor.getValue().getExternalBoxId()).isNull();
    }

    @Test
    void upsert_threeKeyReturnsMultipleLines_storesUnlinked() {
        // D22: 합포장으로 같은 옵션이 여러 박스에 걸리면 모호하다 → 틀린 라인에 붙이느니 미연결
        given(orderClaimRepository.findByMarketplaceAccount_IdAndExternalClaimIdAndExternalItemId(
                1L, "R-1", "V-1")).willReturn(Optional.empty());
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(
                1L, "O-1", "V-1")).willReturn(List.of(orderLine(12L), orderLine(13L)));

        upserter.upsert(account, ClaimType.RETURN, record(null, ClaimStatus.RECEIVED, "UC", 1));

        ArgumentCaptor<OrderClaim> captor = ArgumentCaptor.forClass(OrderClaim.class);
        verify(orderClaimRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderItem()).isNull();
        assertThat(captor.getValue().isLinked()).isFalse();
    }

    @Test
    void upsert_existingClaimWithChangedStatus_updatesMutableFieldsOnly() {
        OrderClaim existing = existingClaim(ClaimStatus.RECEIVED, "UC", 2);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndExternalClaimIdAndExternalItemId(
                1L, "R-1", "V-1")).willReturn(Optional.of(existing));

        upserter.upsert(account, ClaimType.RETURN, record("B-1", ClaimStatus.DONE, "CC", 2));

        ArgumentCaptor<OrderClaim> captor = ArgumentCaptor.forClass(OrderClaim.class);
        verify(orderClaimRepository, times(1)).save(captor.capture());
        OrderClaim saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.DONE);
        assertThat(saved.getPlatformStatus()).isEqualTo("CC");
        // 불변 필드
        assertThat(saved.getReceivedAt()).isEqualTo(existing.getReceivedAt());
        assertThat(saved.getExternalClaimId()).isEqualTo("R-1");
        // 이미 연결된 주문 라인은 재매칭하지 않는다
        verify(orderItemRepository, never())
                .findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                        anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void upsert_existingClaimUnchanged_skipsSave() {
        OrderClaim existing = existingClaim(ClaimStatus.RECEIVED, "UC", 2);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndExternalClaimIdAndExternalItemId(
                1L, "R-1", "V-1")).willReturn(Optional.of(existing));

        upserter.upsert(account, ClaimType.RETURN, record("B-1", ClaimStatus.RECEIVED, "UC", 2));

        verify(orderClaimRepository, never()).save(any());
    }

    @Test
    void relink_fourKeyMatches_linksWithoutConsumingAttempt() {
        OrderItem line = orderLine(20L);
        given(orderClaimRepository.findById(1L)).willReturn(Optional.of(unlinked(1L)));
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                1L, "B-1", "O-1", "V-1")).willReturn(Optional.of(line));

        boolean linked = upserter.relink(1L);

        assertThat(linked).isTrue();
        ArgumentCaptor<OrderClaim> captor = ArgumentCaptor.forClass(OrderClaim.class);
        verify(orderClaimRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderItem()).isSameAs(line);
        // 성공은 시도횟수를 소모하지 않는다 — 포기 조건(D13)은 실패에만 걸린다
        assertThat(captor.getValue().getOrderItemMatchAttempts()).isZero();
    }

    @Test
    void relink_ambiguousThreeKey_incrementsAttemptsAndStaysUnlinked() {
        given(orderClaimRepository.findById(1L)).willReturn(Optional.of(unlinked(1L)));
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                1L, "B-1", "O-1", "V-1")).willReturn(Optional.empty());
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(
                1L, "O-1", "V-1")).willReturn(List.of(orderLine(21L), orderLine(22L)));

        boolean linked = upserter.relink(1L);

        assertThat(linked).isFalse();
        ArgumentCaptor<OrderClaim> captor = ArgumentCaptor.forClass(OrderClaim.class);
        verify(orderClaimRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderItem()).isNull();
        assertThat(captor.getValue().getOrderItemMatchAttempts()).isEqualTo(1);
    }

    /** 04 백필 대상 — 주문 라인이 아직 붙지 않은 클레임. */
    private OrderClaim unlinked(long id) {
        return OrderClaim.builder()
                .id(id).marketplaceAccount(account).platform("COUPANG").claimType(ClaimType.RETURN)
                .externalClaimId("R-1").externalOrderId("O-1").externalBoxId("B-1").externalItemId("V-1")
                .orderItem(null).orderItemMatchAttempts(0).status(ClaimStatus.RECEIVED)
                .receivedAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
    }

    private OrderItem orderLine(Long id) {
        return OrderItem.builder()
                .id(id).marketplaceAccount(account).platform("COUPANG")
                .externalOrderId("O-1").externalBoxId("B-1").externalItemId("V-1")
                .orderCount(3).cancelCount(0).holdCount(0).status("ACCEPT").build();
    }

    /** 아래 existingClaim 과 같은 값 — 다른 값을 주면 그 필드만 변경 판정된다. */
    private ClaimRecord record(String boxId, ClaimStatus status, String platformStatus, int quantity) {
        return new ClaimRecord("R-1", "O-1", boxId, "V-1", "양말", quantity, status, platformStatus,
                "CHANGEMIND", "단순변심", "CUSTOMER", 3000, "INV-9", "CJGLS", "홍길동",
                LocalDateTime.of(2026, 9, 1, 10, 0), LocalDateTime.of(2026, 9, 1, 10, 0));
    }

    private OrderClaim existingClaim(ClaimStatus status, String platformStatus, int quantity) {
        return OrderClaim.builder()
                .id(99L).marketplaceAccount(account).platform("COUPANG").claimType(ClaimType.RETURN)
                .externalClaimId("R-1").externalOrderId("O-1").externalBoxId("B-1").externalItemId("V-1")
                .orderItem(orderLine(10L)).orderItemMatchAttempts(0)
                .itemName("양말").quantity(quantity).status(status).platformStatus(platformStatus)
                .reasonCode("CHANGEMIND").reasonText("단순변심").faultType("CUSTOMER")
                .returnShippingCharge(3000).collectInvoiceNo("INV-9").collectCarrierCode("CJGLS")
                .requesterName("홍길동")
                .receivedAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .platformModifiedAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .syncedAt(LocalDateTime.of(2026, 9, 1, 12, 0))
                .build();
    }
}
