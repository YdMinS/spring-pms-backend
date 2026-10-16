package com.pms.service.elevenst;

import com.pms.domain.ElevenstCancelRequest;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17: the exact 11st paths of 1634 · 1888 · 1640 · 1641 and their result codes (D2 · D38 · D48). */
@ExtendWith(MockitoExtension.class)
class ElevenstOrderActionsTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    @Mock private ElevenstApiClient client;
    @InjectMocks private ElevenstOrderActions actions;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    private static byte[] result(String code, String text) {
        return ("<ResultOrder><result_code>" + code + "</result_code><result_text>" + text
                + "</result_text></ResultOrder>").getBytes(EUC_KR);
    }

    @Test
    void acknowledge_readsAddPrdFromRaw_andTreatsMinus3206AsDone_D48() {
        ElevenstOrderLine line = ElevenstOrderLine.builder().ordNo("O1").ordPrdSeq("2")
                .raw("<order><ordNo>O1</ordNo><addPrdYn>N</addPrdYn></order>").build();
        String path = "/rest/ordservices/reqpackaging/O1/2/N/null/D1";
        given(client.get(path, account)).willReturn(result("-3206", "이미 발주 처리 되었거나 발주 처리할 건이 없음"));

        actions.acknowledge(account, line, "D1");

        verify(client).get(path, account);
    }

    @Test
    void ship_buildsTheDeliveryPath_andFailsWith11stText() {
        String path = "/rest/ordservices/reqdelivery/202610151030/01/00034/612345678901/D1";
        given(client.get(path, account)).willReturn(result("-3320", "이미 사용된 송장번호입니다"));

        assertThatThrownBy(() -> actions.ship(account, "D1", "00034", "612345678901",
                LocalDateTime.of(2026, 10, 15, 10, 30)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용된 송장번호입니다 (-3320)");
    }

    @Test
    void rejectCancel_sendsParcelMethodAndDayDate() {
        ElevenstCancelRequest claim = ElevenstCancelRequest.builder().ordNo("O1").ordPrdSeq("1").ordPrdCnSeq("77")
                .build();
        String path = "/rest/claimservice/cancelreqreject/O1/1/77/01/20261014/00034/612345678901";
        given(client.get(path, account)).willReturn(result("0", "정상"));

        actions.rejectCancel(account, claim, "00034", "612345678901", LocalDate.of(2026, 10, 14));

        verify(client).get(path, account);
    }
}
