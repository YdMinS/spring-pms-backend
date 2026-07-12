package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.domain.PlatformCarrierCode;
import com.pms.repository.CarrierRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CarrierCodeServiceImplTest {

    @Mock
    private CarrierRepository carrierRepository;

    @Mock
    private PlatformCarrierCodeRepository platformCarrierCodeRepository;

    @InjectMocks
    private CarrierCodeServiceImpl carrierCodeService;

    @Test
    void resolve_happy() {
        Carrier carrier = Carrier.builder().id(1L).name("CJ대한통운").isActive(true).build();
        PlatformCarrierCode code = PlatformCarrierCode.builder()
                .id(1L).carrier(carrier).platform("COUPANG").deliveryCompanyCode("CJGLS").build();
        given(carrierRepository.findByIsActiveTrueOrderByIdAsc()).willReturn(List.of(carrier));
        given(platformCarrierCodeRepository.findByCarrier_IdAndPlatform(1L, "COUPANG"))
                .willReturn(Optional.of(code));

        assertThat(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).isEqualTo("CJGLS");
    }

    @Test
    void resolve_noCode() {
        Carrier carrier = Carrier.builder().id(1L).name("CJ대한통운").isActive(true).build();
        given(carrierRepository.findByIsActiveTrueOrderByIdAsc()).willReturn(List.of(carrier));
        given(platformCarrierCodeRepository.findByCarrier_IdAndPlatform(1L, "COUPANG"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> carrierCodeService.resolveDeliveryCompanyCode("COUPANG"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COUPANG");
    }

    @Test
    void resolve_noActiveCarrier() {
        given(carrierRepository.findByIsActiveTrueOrderByIdAsc()).willReturn(List.of());

        assertThatThrownBy(() -> carrierCodeService.resolveDeliveryCompanyCode("COUPANG"))
                .isInstanceOf(IllegalStateException.class);
    }
}
