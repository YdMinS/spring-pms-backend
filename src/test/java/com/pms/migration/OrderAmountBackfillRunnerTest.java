package com.pms.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.OrderLine;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 금액 백필 러너 (FEATURE_2609_26 / PLAN D13). */
@ExtendWith(MockitoExtension.class)
class OrderAmountBackfillRunnerTest {

    @Mock
    private OrderLineRepository orderLineRepository;
    @Mock
    private CoupangOrderLineRepository coupangOrderLineRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrderAmountBackfillRunner runner(boolean enabled) {
        return new OrderAmountBackfillRunner(orderLineRepository, coupangOrderLineRepository,
                objectMapper, enabled);
    }

    @Test
    void 게이트가꺼져있으면아무것도하지않는다() {
        runner(false).run(null);

        verifyNoInteractions(orderLineRepository, coupangOrderLineRepository);
    }

    @Test
    void 금액이빈행만raw에서복원한다() {
        given(orderLineRepository.findDistinctTenantIds()).willReturn(List.of(1L));
        OrderLine line = OrderLine.builder().id(5L).orderQty(2).cancelQty(0).holdQty(0).build();
        given(orderLineRepository.findAmountBackfillBatch(eq(0L), any(Pageable.class)))
                .willReturn(List.of(line));
        given(orderLineRepository.findAmountBackfillBatch(eq(5L), any(Pageable.class)))
                .willReturn(List.of());
        given(coupangOrderLineRepository.findByOrderLine_Id(5L)).willReturn(Optional.of(
                mirror("""
                    {"salesPrice":{"units":26990,"nanos":0},"orderPrice":{"units":53980,"nanos":0},
                     "discountPrice":{"units":1000,"nanos":0},"coupangDiscount":{"units":1000,"nanos":0}}
                    """)));

        runner(true).run(null);

        ArgumentCaptor<OrderLine> saved = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(saved.capture());
        assertThat(saved.getValue().getUnitPrice()).isEqualByComparingTo("26990");
        assertThat(saved.getValue().getLineAmount()).isEqualByComparingTo("53980");
        assertThat(saved.getValue().getDiscountAmount()).isEqualByComparingTo("1000");
        assertThat(saved.getValue().getPlatformDiscountAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void 파싱실패행은건너뛰고나머지를계속처리한다() {
        given(orderLineRepository.findDistinctTenantIds()).willReturn(List.of(1L));
        OrderLine broken = OrderLine.builder().id(5L).orderQty(1).cancelQty(0).holdQty(0).build();
        OrderLine ok = OrderLine.builder().id(6L).orderQty(1).cancelQty(0).holdQty(0).build();
        given(orderLineRepository.findAmountBackfillBatch(eq(0L), any(Pageable.class)))
                .willReturn(List.of(broken, ok));
        given(orderLineRepository.findAmountBackfillBatch(eq(6L), any(Pageable.class)))
                .willReturn(List.of());
        given(coupangOrderLineRepository.findByOrderLine_Id(5L))
                .willReturn(Optional.of(mirror("not-json")));
        given(coupangOrderLineRepository.findByOrderLine_Id(6L))
                .willReturn(Optional.of(mirror("{\"salesPrice\":{\"units\":100,\"nanos\":0}}")));

        runner(true).run(null);

        ArgumentCaptor<OrderLine> saved = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(6L);              // 깨진 행은 저장되지 않는다
        assertThat(saved.getValue().getUnitPrice()).isEqualByComparingTo("100");
    }

    @Test
    void 거울행이없으면건너뛴다() {
        given(orderLineRepository.findDistinctTenantIds()).willReturn(List.of(1L));
        given(orderLineRepository.findAmountBackfillBatch(anyLong(), any(Pageable.class)))
                .willReturn(List.of(OrderLine.builder().id(5L).orderQty(1).cancelQty(0).holdQty(0).build()))
                .willReturn(List.of());
        given(coupangOrderLineRepository.findByOrderLine_Id(5L)).willReturn(Optional.empty());

        runner(true).run(null);

        verify(orderLineRepository, never()).save(any(OrderLine.class));
    }

    private CoupangOrderLine mirror(String raw) {
        return CoupangOrderLine.builder().id(1L).raw(raw).build();
    }
}
