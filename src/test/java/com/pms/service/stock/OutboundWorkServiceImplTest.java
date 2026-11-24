package com.pms.service.stock;

import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.OutboundWorkRequest;
import com.pms.dto.request.StockMovementRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** OutboundWorkServiceImpl (FEATURE_2611_34 D35 ⑤) — every line becomes a disposal row through the recorder. */
@ExtendWith(MockitoExtension.class)
class OutboundWorkServiceImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    @Mock private WorkLineRecorder workLineRecorder;

    @InjectMocks private OutboundWorkServiceImpl service;

    private static OutboundWorkRequest.Line line(long productId, StockReason reason, int quantity) {
        return new OutboundWorkRequest.Line(productId, 3L, reason, null, quantity);
    }

    @Test
    void testSaveRecordsEveryLineAsDisposal() {
        service.save(new OutboundWorkRequest(DAY,
                List.of(line(7L, StockReason.DAMAGED, 1), line(8L, StockReason.SUPPLIER_RETURN, 2))));

        ArgumentCaptor<StockMovementRequest> captor = ArgumentCaptor.forClass(StockMovementRequest.class);
        verify(workLineRecorder, times(2)).record(captor.capture());
        StockMovementRequest first = captor.getAllValues().get(0);
        StockMovementRequest second = captor.getAllValues().get(1);
        assertThat(first.movementType()).isEqualTo(StockMovementType.DISPOSAL);
        assertThat(first.reason()).isEqualTo(StockReason.DAMAGED);
        assertThat(first.quantity()).isEqualTo(1);
        assertThat(first.orderClaimId()).isNull();
        assertThat(second.movementType()).isEqualTo(StockMovementType.DISPOSAL);
        assertThat(second.reason()).isEqualTo(StockReason.SUPPLIER_RETURN);
        assertThat(second.quantity()).isEqualTo(2);
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.sellerId()).isEqualTo(3L);
            assertThat(r.movedOn()).isEqualTo(DAY);
        });
    }

    @Test
    void testSaveRejectsExchangeShipReason() {
        assertThatThrownBy(() -> service.save(new OutboundWorkRequest(DAY,
                List.of(line(7L, StockReason.EXCHANGE_SHIP, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("출고 작업 사유는 폐기 사유와 매입처 반품만 고를 수 있습니다.");
        verify(workLineRecorder, never()).record(any());
    }

    @Test
    void testSaveStopsWhenStockLinkOff() {
        doThrow(new IllegalArgumentException("재고 연동이 꺼져 있습니다.")).when(workLineRecorder).requireStockLinked();

        assertThatThrownBy(() -> service.save(new OutboundWorkRequest(DAY,
                List.of(line(7L, StockReason.DAMAGED, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고 연동이 꺼져 있습니다.");
        verify(workLineRecorder, never()).record(any());
    }
}
