package com.pms.service.claim;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.CollectInvoiceSource;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.Platform;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.OrderClaimRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ClaimCollectInvoiceRecorder — 형제 라인 전체 기록(D10)과 상태 불변(D11).
 */
@ExtendWith(MockitoExtension.class)
class ClaimCollectInvoiceRecorderTest {

    @Mock private OrderClaimRepository orderClaimRepository;
    @InjectMocks private ClaimCollectInvoiceRecorder recorder;

    @Test
    @SuppressWarnings("unchecked")
    void record_writesToEverySiblingLineWithoutTouchingStatus() {
        List<OrderClaim> siblings = List.of(claim(1L), claim(2L), claim(3L));

        recorder.record(siblings, "CJGLS", " 123456789012 ", CollectInvoiceSource.LOCAL);

        ArgumentCaptor<List<OrderClaim>> saved = ArgumentCaptor.forClass(List.class);
        verify(orderClaimRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(3);
        assertThat(saved.getValue()).allSatisfy(claim -> {
            assertThat(claim.getCollectInvoiceNo()).isEqualTo("123456789012");   // 공백은 다듬는다
            assertThat(claim.getCollectCarrierCode()).isEqualTo("CJGLS");
            assertThat(claim.getCollectInvoiceSource()).isEqualTo(CollectInvoiceSource.LOCAL);
            // 🔴 D11 — status·platformStatus 는 건드리지 않는다(추측한 다음 상태를 쓰지 않는다).
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.RECEIVED);
            assertThat(claim.getPlatformStatus()).isEqualTo("RETURNS_COMPLETED");
        });
        assertThat(saved.getValue()).extracting(OrderClaim::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void record_blankInvoiceNumber_throwsWithoutSaving() {
        assertThatThrownBy(() -> recorder.record(List.of(claim(1L)), "CJGLS", "  ",
                CollectInvoiceSource.LOCAL))
                .isInstanceOf(IllegalArgumentException.class);

        verify(orderClaimRepository, never()).saveAll(anyList());
    }

    private OrderClaim claim(Long id) {
        MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A001", null)
                .id(1L).platform(Platform.COUPANG).build();
        return OrderClaim.builder()
                .id(id)
                .marketplaceAccount(account)
                .platform(Platform.COUPANG)
                .claimType(ClaimType.RETURN)
                .externalClaimId("777")
                .externalOrderId("O-1")
                .externalItemId("V-" + id)
                .quantity(1)
                .status(ClaimStatus.RECEIVED)
                .platformStatus("RETURNS_COMPLETED")
                .returnDeliveryType("수기관리")
                .receivedAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
    }
}
