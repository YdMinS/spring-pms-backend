package com.pms.service.elevenst;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.dto.request.ElevenstSellerReturnRequest;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.CarrierCodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.Charset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D35 · D36 · D39 ⑤: eligibility, carrier + invoice together, claimProcess and null values. */
@ExtendWith(MockitoExtension.class)
class ElevenstSellerReturnServiceTest {

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ElevenstOrderLineRepository elevenstOrderLineRepository;
    @Mock private CarrierCodeService carrierCodeService;
    @Mock private ElevenstApiClient client;
    @InjectMocks private ElevenstSellerReturnService service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    private OrderLine line(OrderStatus status) {
        Order order = Order.builder().marketplaceAccount(account).externalOrderId("O1").build();
        return OrderLine.builder().id(10L).order(order).status(status).orderQty(2).cancelQty(0).holdQty(0).build();
    }

    @Test
    void paidLine_isNotEligible() {
        given(orderLineRepository.findWithAccountAndSellerById(10L)).willReturn(Optional.of(line(OrderStatus.PAID)));

        assertThatThrownBy(() -> service.request(10L,
                new ElevenstSellerReturnRequest("101", "변심", "03", null, null, false)))
                .hasMessage("반품신청 가능한 상태가 아닙니다");
    }

    @Test
    void carrierWithoutInvoice_is400() {
        given(orderLineRepository.findWithAccountAndSellerById(10L)).willReturn(Optional.of(line(OrderStatus.SHIPPED)));
        given(elevenstOrderLineRepository.findByOrderLine_Id(10L)).willReturn(Optional.of(
                ElevenstOrderLine.builder().ordNo("O1").ordPrdSeq("1").build()));

        assertThatThrownBy(() -> service.request(10L,
                new ElevenstSellerReturnRequest("101", "변심", "03", "00034", null, false)))
                .hasMessage("택배사와 송장번호를 함께 넣어 주세요.");
        verify(client, never()).getPreEncoded(anyString(), any());
    }

    @Test
    void completeNow_withoutPickup_sendsProcess02AndNullFields() {
        given(orderLineRepository.findWithAccountAndSellerById(10L)).willReturn(Optional.of(line(OrderStatus.DELIVERED)));
        given(elevenstOrderLineRepository.findByOrderLine_Id(10L)).willReturn(Optional.of(
                ElevenstOrderLine.builder().ordNo("O1").ordPrdSeq("1").build()));
        given(client.getPreEncoded(anyString(), eq(account))).willReturn(
                "<ResultOrder><result_code>0</result_code></ResultOrder>".getBytes(Charset.forName("EUC-KR")));

        service.request(10L, new ElevenstSellerReturnRequest("116", "불량", "04", null, null, true));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getPreEncoded(path.capture(), eq(account));
        assertThat(path.getValue()).isEqualTo("/rest/claimservice/sellerclaimfix/O1/1/116/02/null/null/null/"
                + "%BA%D2%B7%AE/04");
    }
}
