package com.pms.service.elevenst;

import com.pms.domain.ElevenstCancelRequest;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.ElevenstCancelRequestRepository;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D14 · D40: cancel claims → table + re-summed hold / cancel quantities. */
@ExtendWith(MockitoExtension.class)
class ElevenstCancelRequestUpserterTest {

    @Mock private ElevenstCancelRequestRepository cancelRequestRepository;
    @Mock private ElevenstOrderLineRepository elevenstOrderLineRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @InjectMocks private ElevenstCancelRequestUpserter upserter;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();
    private final OrderLine line = OrderLine.builder().id(1L).orderQty(5).cancelQty(0).holdQty(0).build();

    @BeforeEach
    void setUp() {
        lenient().when(elevenstOrderLineRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(5L, "O1", "1"))
                .thenReturn(Optional.of(ElevenstOrderLine.builder().id(9L).orderLine(line).build()));
    }

    private ElevenstCancelRequest claim(String seq, String status, int qty) {
        return ElevenstCancelRequest.builder().id(Long.valueOf(seq)).marketplaceAccount(account).orderLine(line)
                .ordNo("O1").ordPrdSeq("1").ordPrdCnSeq(seq).quantity(qty).platformStatus(status).build();
    }

    @Test
    void newRequest_isStored_andHoldIsTheSumOfOpenRequests() {
        given(cancelRequestRepository.findByMarketplaceAccount_IdAndOrdPrdCnSeq(5L, "77")).willReturn(Optional.empty());
        given(cancelRequestRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(5L, "O1", "1"))
                .willReturn(List.of(claim("77", "01", 2), claim("66", "02", 1), claim("55", "05", 3)));

        int changed = upserter.upsertRows(account, List.of(Map.of("ordPrdCnSeq", "77", "ordNo", "O1",
                "ordPrdSeq", "1", "ordCnQty", "2", "ordCnRsnCd", "08", "ordCnMnbdCd", "01",
                "createDt", "2026-10-15 10:00:00", "ordCnStatCd", "01")), ElevenstCancelRequest.STATUS_REQUESTED);

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<ElevenstCancelRequest> stored = ArgumentCaptor.forClass(ElevenstCancelRequest.class);
        verify(cancelRequestRepository).save(stored.capture());
        assertThat(stored.getValue().getReasonCode()).isEqualTo("08");
        assertThat(stored.getValue().getRequestedBy()).isEqualTo("01");
        ArgumentCaptor<OrderLine> saved = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(saved.capture());
        assertThat(saved.getValue().getHoldQty()).isEqualTo(2);
        assertThat(saved.getValue().getCancelQty()).isEqualTo(1);
    }

    @Test
    void rejectedClaim_isNotReopenedByARequestRow() {
        ElevenstCancelRequest rejected = claim("77", ElevenstCancelRequest.STATUS_REJECTED, 2);
        given(cancelRequestRepository.findByMarketplaceAccount_IdAndOrdPrdCnSeq(5L, "77")).willReturn(Optional.of(rejected));
        given(cancelRequestRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(5L, "O1", "1"))
                .willReturn(List.of(rejected));

        upserter.upsertRows(account, List.of(Map.of("ordPrdCnSeq", "77", "ordNo", "O1", "ordPrdSeq", "1")),
                ElevenstCancelRequest.STATUS_REQUESTED);

        verify(cancelRequestRepository, never()).save(any());
        verify(orderLineRepository, never()).save(any());
    }

    @Test
    void completedRow_overwritesRejected_andMovesQuantityToCancel() {
        ElevenstCancelRequest rejected = claim("77", ElevenstCancelRequest.STATUS_REJECTED, 2);
        given(cancelRequestRepository.findByMarketplaceAccount_IdAndOrdPrdCnSeq(5L, "77")).willReturn(Optional.of(rejected));
        given(cancelRequestRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(5L, "O1", "1"))
                .willReturn(List.of(claim("77", "02", 2)));

        upserter.upsertRows(account, List.of(Map.of("ordPrdCnSeq", "77", "ordNo", "O1", "ordPrdSeq", "1",
                "cnTrtEndDt", "2026-10-15 11:00:00")), ElevenstCancelRequest.STATUS_COMPLETED);

        ArgumentCaptor<ElevenstCancelRequest> stored = ArgumentCaptor.forClass(ElevenstCancelRequest.class);
        verify(cancelRequestRepository).save(stored.capture());
        assertThat(stored.getValue().getPlatformStatus()).isEqualTo("02");
        ArgumentCaptor<OrderLine> saved = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(saved.capture());
        assertThat(saved.getValue().getCancelQty()).isEqualTo(2);
        assertThat(saved.getValue().getHoldQty()).isZero();
    }
}
