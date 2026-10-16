package com.pms.service.elevenst;

import com.pms.domain.ElevenstCancelRequest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.request.ElevenstCancelRejectRequest;
import com.pms.dto.response.ElevenstCancelRequestResponse;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.ElevenstCancelRequestRepository;
import com.pms.service.CarrierCodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D19 · D37 · D38 · D40 · D41. */
@ExtendWith(MockitoExtension.class)
class ElevenstCancelRequestServiceImplTest {

    @Mock private ElevenstCancelRequestRepository cancelRequestRepository;
    @Mock private ElevenstCancelRequestUpserter cancelRequestUpserter;
    @Mock private ElevenstOrderActions elevenstOrderActions;
    @Mock private CarrierCodeService carrierCodeService;
    @InjectMocks private ElevenstCancelRequestServiceImpl service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    private ElevenstCancelRequest claim(String status) {
        return ElevenstCancelRequest.builder().id(7L).marketplaceAccount(account).ordNo("O1").ordPrdSeq("1")
                .ordPrdCnSeq("77").quantity(2).requestedBy("02").reasonCode("08").reasonText("전 옵션 품절")
                .requestedAt(LocalDateTime.of(2026, 10, 15, 10, 0)).platformStatus(status).build();
    }

    @Test
    void listOpen_labelsTheReasonByWhoFiledIt_D41() {
        given(cancelRequestRepository.findByOrderLine_IdInAndPlatformStatus(List.of(10L), "01"))
                .willReturn(List.of(claim("01")));

        List<ElevenstCancelRequestResponse> open = service.listOpen(10L);

        assertThat(open).extracting(ElevenstCancelRequestResponse::reason)
                .containsExactly("상품 품절(전체옵션) — 전 옵션 품절");
    }

    @Test
    void approve_callsElevenstThenMarksCompleted() {
        ElevenstCancelRequest open = claim("01");
        given(cancelRequestRepository.findWithAccountById(7L)).willReturn(Optional.of(open));

        service.approve(7L);

        verify(elevenstOrderActions).approveCancel(account, open);
        verify(cancelRequestUpserter).markApproved(eq(7L), any());
    }

    @Test
    void approve_closedRequest_is409_andNothingIsSent() {
        given(cancelRequestRepository.findWithAccountById(7L)).willReturn(Optional.of(claim("02")));

        assertThatThrownBy(() -> service.approve(7L)).isInstanceOf(BusinessException.class)
                .hasMessage("이미 처리된 접수입니다");
        verify(elevenstOrderActions, never()).approveCancel(any(), any());
    }

    @Test
    void reject_shortInvoice_is400_beforeCalling11st() {
        given(cancelRequestRepository.findWithAccountById(7L)).willReturn(Optional.of(claim("01")));
        given(carrierCodeService.validateDeliveryCompanyCode("00034", Platform.ELEVENST)).willReturn("00034");

        assertThatThrownBy(() -> service.reject(7L, new ElevenstCancelRejectRequest("00034", "1234-567890", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("송장번호가 10자리 이하입니다.");
        verify(elevenstOrderActions, never()).rejectCancel(any(), any(), any(), any(), any());
    }

    @Test
    void reject_sendsNormalizedInvoiceAndDate_thenMarksRejected() {
        ElevenstCancelRequest open = claim("01");
        given(cancelRequestRepository.findWithAccountById(7L)).willReturn(Optional.of(open));
        given(carrierCodeService.validateDeliveryCompanyCode("00034", Platform.ELEVENST)).willReturn("00034");

        service.reject(7L, new ElevenstCancelRejectRequest("00034", "6123-4567-8901", LocalDate.of(2026, 10, 14)));

        verify(elevenstOrderActions).rejectCancel(account, open, "00034", "612345678901", LocalDate.of(2026, 10, 14));
        verify(cancelRequestUpserter).markRejected(7L);
    }
}
