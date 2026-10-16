package com.pms.service.claim;

import com.pms.domain.ClaimAction;
import com.pms.domain.ClaimType;
import com.pms.domain.CollectInvoiceSource;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.Platform;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.OrderLineRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.elevenst.ElevenstApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D15 · D22 · D23 · D24 · D34 · D39: 11st action list by status and the exact 11st calls. */
@ExtendWith(MockitoExtension.class)
class ElevenstClaimActionAdapterTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    @Mock private ElevenstApiClient client;
    @Mock private CarrierCodeService carrierCodeService;
    @Mock private OrderLineRepository orderLineRepository;
    @InjectMocks private ElevenstClaimActionAdapter adapter;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    private OrderClaim claim(ClaimType type, String clmStat, OrderLine line) {
        return OrderClaim.builder().id(1L).marketplaceAccount(account).platform(Platform.ELEVENST).claimType(type)
                .externalClaimId("C1").externalOrderId("O1").externalItemId("2").platformStatus(clmStat)
                .orderLine(line).build();
    }

    private static byte[] ok() {
        return "<ResultOrder><result_code>0</result_code><result_text/></ResultOrder>".getBytes(EUC_KR);
    }

    @Test
    void returnRequested_offersApproveRejectHoldAndInvoice_withRejectHint() {
        OrderLine line = OrderLine.builder().id(10L).build();

        List<ClaimActionOption> options = adapter.availableActions(claim(ClaimType.RETURN, "105", line), Set.of());

        assertThat(options).extracting(ClaimActionOption::action).containsExactly(ClaimAction.RETURN_APPROVE,
                ClaimAction.RETURN_REJECT, ClaimAction.RETURN_HOLD, ClaimAction.RETURN_COLLECT_INVOICE);
        ClaimActionOption reject = options.get(1);
        assertThat(reject.requires()).isEqualTo(ClaimAction.Requires.REASON);
        assertThat(reject.irreversible()).isTrue();
        assertThat(reject.hint()).isEqualTo("입력한 사유는 고객에게 문자 · 메일로 전달됩니다.");
    }

    @Test
    void returnOnHold_offersCompletionHold_andNoInvoiceWithoutOrderLine() {
        List<ClaimActionOption> options = adapter.availableActions(claim(ClaimType.RETURN, "104", null), Set.of());

        assertThat(options).extracting(ClaimActionOption::action).containsExactly(ClaimAction.RETURN_APPROVE,
                ClaimAction.RETURN_REJECT, ClaimAction.RETURN_COMPLETE_HOLD);
    }

    @Test
    void localPickupInvoice_offersResendWithMarketLabel_D22() {
        OrderClaim local = claim(ClaimType.RETURN, "109", OrderLine.builder().id(10L).build()).toBuilder()
                .collectInvoiceNo("123").collectInvoiceSource(CollectInvoiceSource.LOCAL).build();

        assertThat(adapter.availableActions(local, Set.of()))
                .filteredOn(o -> o.action() == ClaimAction.RETURN_COLLECT_INVOICE_RESEND)
                .extracting(ClaimActionOption::label).containsExactly("마켓에 다시 보내기");
    }

    @Test
    void exchangeRequested_offersRejectAndApproveWithInvoice_D24() {
        List<ClaimActionOption> options = adapter.availableActions(claim(ClaimType.EXCHANGE, "201", null), Set.of());

        assertThat(options).extracting(ClaimActionOption::label)
                .containsExactly("교환 거부", "교환 승인(재발송 송장 등록)");
        assertThat(options).allMatch(ClaimActionOption::irreversible);
    }

    @Test
    void returnReject_sendsEucKrEncodedReason_D39() {
        given(client.getPreEncoded(anyString(), eq(account))).willReturn(ok());

        ClaimActionOutcome outcome = adapter.execute(account, List.of(claim(ClaimType.RETURN, "105", null)),
                new ClaimActionCommand(ClaimAction.RETURN_REJECT, null, null, null, "101", "상품 미입고"));

        assertThat(outcome.succeeded()).isTrue();
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getPreEncoded(path.capture(), eq(account));
        assertThat(path.getValue()).isEqualTo("/rest/claimservice/returnreqreject/O1/2/C1/101/"
                + "%BB%F3%C7%B0%20%B9%CC%C0%D4%B0%ED");
    }

    @Test
    void pickupInvoice_usesTheLinkedLinesDeliveryNumber_andAReturnsRefusalIsAFailedOutcome() {
        Order order = Order.builder().marketplaceAccount(account).externalOrderId("O1").build();
        OrderLine line = OrderLine.builder().id(10L).order(order)
                .orderShipment(OrderShipment.builder().order(order).externalShipmentId("D9").build()).build();
        given(orderLineRepository.findWithAccountAndSellerById(10L)).willReturn(Optional.of(line));
        given(carrierCodeService.validateDeliveryCompanyCode("00034", Platform.ELEVENST)).willReturn("00034");
        given(client.getPreEncoded(anyString(), eq(account))).willReturn(
                "<ResultOrder><result_code>-3310</result_code><result_text>해당 배송번호의 주문상태를 확인해 주세요</result_text></ResultOrder>"
                        .getBytes(EUC_KR));

        ClaimActionOutcome outcome = adapter.execute(account, List.of(claim(ClaimType.RETURN, "105", line)),
                new ClaimActionCommand(ClaimAction.RETURN_COLLECT_INVOICE, "00034", "6123-45", null, null, null));

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.resultMessage()).isEqualTo("해당 배송번호의 주문상태를 확인해 주세요");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).getPreEncoded(path.capture(), any());
        assertThat(path.getValue()).matches("/rest/claimservice/claimservice/returninvc/\\d{12}/D9/00034/612345/01/C1");
    }
}
