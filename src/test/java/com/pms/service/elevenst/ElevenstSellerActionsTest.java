package com.pms.service.elevenst;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.fixture.MarketplaceAccountFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D61 · D63 · D64 · D66 · D67: the exact 11st paths and result codes of the seller actions. */
@ExtendWith(MockitoExtension.class)
class ElevenstSellerActionsTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    @Mock private ElevenstApiClient client;
    @InjectMocks private ElevenstSellerActions actions;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    private static byte[] result(String code, String text) {
        return ("<ResultOrder><result_code>" + code + "</result_code><result_text>" + text
                + "</result_text></ResultOrder>").getBytes(EUC_KR);
    }

    @Test
    void rejectOrder_encodesTheReasonTextInEucKr_D61() {
        ElevenstOrderLine line = ElevenstOrderLine.builder().ordNo("O1").ordPrdSeq("1").build();
        String path = "/rest/claimservice/reqrejectorder/O1/1/08/%C7%B0%C0%FD";
        given(client.getPreEncoded(path, account)).willReturn(result("0", "전체 1건이 정상적으로 발송처리가 되었습니다."));

        actions.rejectOrder(account, line, "08", "품절");

        verify(client).getPreEncoded(path, account);
    }

    @Test
    void deliveryDelay_postsTheXmlBodyWithDayDate_D63() {
        given(client.post(eq(ElevenstPaths.DELIVERY_DELAY), anyString(), eq(account)))
                .willReturn(result("0", "전체 1 건이 발송지연 처리 되었습니다."));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        actions.deliveryDelay(account, "O1", "D1", LocalDate.of(2026, 10, 20), "01", "재고가 곧 들어옵니다");

        verify(client).post(eq(ElevenstPaths.DELIVERY_DELAY), body.capture(), eq(account));
        assertThat(body.getValue()).isEqualTo("<DeliveryDelayGuideRequest><ordNo>O1</ordNo><dlvNo>D1</dlvNo>"
                + "<delaySendDt>20261020</delaySendDt><delaySendRsnCd>01</delaySendRsnCd>"
                + "<delaySendRsn>재고가 곧 들어옵니다</delaySendRsn></DeliveryDelayGuideRequest>");
    }

    @Test
    void shipPartial_joinsTheChosenLines_D64() {
        String path = "/rest/ordservices/reqdelivery/202610151030/01/00034/612345678901/D1/Y/O1/1,3";
        given(client.get(path, account)).willReturn(result("0", "정상"));

        actions.shipPartial(account, "D1", "O1", List.of("1", "3"), "00034", "612345678901",
                LocalDateTime.of(2026, 10, 15, 10, 30));

        verify(client).get(path, account);
    }

    @Test
    void shipSplit_firstInvoiceInThePath_restInInvcAddData_D64() {
        String path = "/rest/ordservices/reqdelivery/202610151030/01/00034/111111111111/D1/1/O1/2/"
                + "222222222222%402%5E333333333333%401";
        given(client.getPreEncoded(path, account)).willReturn(result("0", "정상"));

        actions.shipSplit(account, "D1", "O1", "2", "00034", List.of(
                new ElevenstSellerActions.SplitInvoice("111111111111", 1),
                new ElevenstSellerActions.SplitInvoice("222222222222", 2),
                new ElevenstSellerActions.SplitInvoice("333333333333", 1)), LocalDateTime.of(2026, 10, 15, 10, 30));

        verify(client).getPreEncoded(path, account);
    }

    @Test
    void visitPickup_successIsResultCode1_otherwise11stText_D66() {
        String path = "/rest/ordservices/visitdlvend/D1/O1/A12";
        given(client.postPreEncoded(path, account)).willReturn(("<ns2:orders xmlns:ns2=\"http://x\">"
                + "<ns2:result_code>-1001</ns2:result_code>"
                + "<ns2:result_text>방문수령인증코드가 유효하지 않습니다. 다시 한 번 확인해주세요.</ns2:result_text>"
                + "</ns2:orders>").getBytes(EUC_KR));

        assertThatThrownBy(() -> actions.visitPickup(account, "D1", "O1", "A12"))
                .isInstanceOf(ElevenstRejectedException.class)
                .hasMessage("방문수령인증코드가 유효하지 않습니다. 다시 한 번 확인해주세요.");
    }

    @Test
    void stockConfirm_minusOneCountsAsDone_D67() {
        ElevenstOrderLine line = ElevenstOrderLine.builder().ordNo("O1").ordPrdSeq("1").build();
        String path = "/rest/ordservices/saleconfirm/O1/1";
        given(client.get(path, account)).willReturn(result("-1", "이미 입고완료 처리 되었거나, 입고완료 처리할 건이 없습니다."));

        actions.stockConfirm(account, line);

        verify(client).get(path, account);
    }
}
