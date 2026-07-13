package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.dto.request.CarrierRateRequest;
import com.pms.dto.response.CarrierRateResponse;
import com.pms.exception.CarrierNotFoundException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class CarrierRateServiceTest {

    @Mock
    private CarrierRateRepository carrierRateRepository;

    @Mock
    private CarrierRepository carrierRepository;

    @InjectMocks
    private CarrierRateServiceImpl carrierRateService;

    private Carrier carrier;

    @BeforeEach
    public void setUp() {
        carrier = Carrier.builder().id(4L).name("롯데택배").isActive(true).build();
    }

    private CarrierRateRequest.CarrierRateRequestBuilder requestBuilder() {
        return CarrierRateRequest.builder()
                .carrierId(4L)
                .type("EXPRESS")
                .cost(new BigDecimal("15.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false);
    }

    @Test
    public void create_resolvesCarrierById() {
        given(carrierRepository.findById(4L)).willReturn(Optional.of(carrier));
        given(carrierRateRepository.save(any(CarrierRate.class)))
                .willAnswer(inv -> inv.<CarrierRate>getArgument(0).toBuilder().id(1L).build());

        CarrierRateResponse response = carrierRateService.createCarrierRate(requestBuilder().build());

        ArgumentCaptor<CarrierRate> captor = ArgumentCaptor.forClass(CarrierRate.class);
        verify(carrierRateRepository).save(captor.capture());
        assertThat(captor.getValue().getCarrier()).isSameAs(carrier);

        assertThat(response.getCarrierId()).isEqualTo(4L);
        assertThat(response.getCarrier()).isEqualTo("롯데택배");
    }

    @Test
    public void create_carrierNotFound_throws() {
        CarrierRateRequest req = requestBuilder().carrierId(999L).build();
        given(carrierRepository.findById(999L)).willReturn(Optional.empty());

        assertThrows(CarrierNotFoundException.class,
                () -> carrierRateService.createCarrierRate(req));
    }

    @Test
    public void create_isDefaultTrue_demotesExistingDefault() {
        Carrier other = Carrier.builder().id(7L).name("한진택배").isActive(true).build();
        CarrierRate existingDefault = CarrierRate.builder()
                .id(9L)
                .carrier(other)
                .type("STANDARD")
                .cost(new BigDecimal("10.00"))
                .effectiveDate(LocalDate.now())
                .isDefault(true)
                .build();
        given(carrierRateRepository.findByIsDefaultTrue()).willReturn(Optional.of(existingDefault));
        given(carrierRepository.findById(4L)).willReturn(Optional.of(carrier));
        given(carrierRateRepository.save(any(CarrierRate.class)))
                .willAnswer(inv -> inv.getArgument(0));

        carrierRateService.createCarrierRate(requestBuilder().isDefault(true).build());

        ArgumentCaptor<CarrierRate> captor = ArgumentCaptor.forClass(CarrierRate.class);
        verify(carrierRateRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        // First save demotes the existing default, second saves the new default.
        assertThat(captor.getAllValues().get(0).getIsDefault()).isFalse();
        assertThat(captor.getAllValues().get(1).getIsDefault()).isTrue();
    }

    @Test
    public void getCarrierRate_notFound_throws() {
        given(carrierRateRepository.findById(99L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> carrierRateService.getCarrierRate(99L));
    }

    @Test
    public void update_resolvesCarrierById() {
        CarrierRate existing = CarrierRate.builder()
                .id(1L)
                .carrier(carrier)
                .type("EXPRESS")
                .cost(new BigDecimal("15.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();
        Carrier newCarrier = Carrier.builder().id(8L).name("우체국택배").isActive(true).build();
        given(carrierRateRepository.findById(1L)).willReturn(Optional.of(existing));
        given(carrierRepository.findById(8L)).willReturn(Optional.of(newCarrier));
        given(carrierRateRepository.save(any(CarrierRate.class)))
                .willAnswer(inv -> inv.getArgument(0));

        CarrierRateResponse response =
                carrierRateService.updateCarrierRate(1L, requestBuilder().carrierId(8L).build());

        assertThat(response.getCarrierId()).isEqualTo(8L);
        assertThat(response.getCarrier()).isEqualTo("우체국택배");
    }
}
