package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.dto.request.CarrierRequest;
import com.pms.dto.response.CarrierResponse;
import com.pms.exception.CarrierNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class CarrierServiceImplTest {

    @Mock
    private CarrierRepository carrierRepository;

    @Mock
    private CarrierRateRepository carrierRateRepository;

    @Mock
    private PlatformCarrierCodeRepository platformCarrierCodeRepository;

    @InjectMocks
    private CarrierServiceImpl carrierService;

    @Test
    public void createCarrier_savesAndReturns() {
        CarrierRequest request = CarrierRequest.builder()
                .name("롯데택배")
                .isActive(true)
                .build();
        Carrier saved = Carrier.builder().id(1L).name("롯데택배").isActive(true).build();
        given(carrierRepository.save(any(Carrier.class))).willReturn(saved);

        CarrierResponse response = carrierService.createCarrier(request);

        assertThat(response.getName()).isEqualTo("롯데택배");
        assertThat(response.getIsActive()).isTrue();
        verify(carrierRepository).save(any(Carrier.class));
    }

    @Test
    public void getCarrier_notFound_throws() {
        given(carrierRepository.findById(99L)).willReturn(Optional.empty());

        assertThrows(CarrierNotFoundException.class, () -> carrierService.getCarrier(99L));
    }

    @Test
    public void updateCarrier_replacesFields() {
        Carrier existing = Carrier.builder().id(1L).name("롯데택배").isActive(true).build();
        given(carrierRepository.findById(1L)).willReturn(Optional.of(existing));
        given(carrierRepository.save(any(Carrier.class))).willAnswer(inv -> inv.getArgument(0));

        CarrierRequest request = CarrierRequest.builder()
                .name("CJ대한통운")
                .isActive(false)
                .build();

        carrierService.updateCarrier(1L, request);

        ArgumentCaptor<Carrier> captor = ArgumentCaptor.forClass(Carrier.class);
        verify(carrierRepository).save(captor.capture());
        Carrier persisted = captor.getValue();
        assertThat(persisted.getId()).isEqualTo(1L);
        assertThat(persisted.getName()).isEqualTo("CJ대한통운");
        assertThat(persisted.getIsActive()).isFalse();
    }

    @Test
    public void deleteCarrier_notFound_throws() {
        given(carrierRepository.findById(99L)).willReturn(Optional.empty());

        assertThrows(CarrierNotFoundException.class, () -> carrierService.deleteCarrier(99L));
    }

    @Test
    public void deleteCarrier_cascadesPlatformCodes() {
        Carrier existing = Carrier.builder().id(1L).name("롯데택배").isActive(true).build();
        given(carrierRepository.findById(1L)).willReturn(Optional.of(existing));
        given(carrierRateRepository.existsByCarrierId(1L)).willReturn(false);

        carrierService.deleteCarrier(1L);

        // 자식 플랫폼 코드 cascade 삭제 후 carrier 삭제.
        verify(platformCarrierCodeRepository).deleteByCarrier_Id(1L);
        verify(carrierRepository).delete(existing);
    }
}
