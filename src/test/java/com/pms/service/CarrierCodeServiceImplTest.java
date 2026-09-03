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

    @Test
    void findOptions_쿠팡은_전체코드표에_등록택배사가_맨위() {
        Carrier lotte = Carrier.builder().id(2L).name("롯데(로컬 표기)").isActive(true).build();
        given(platformCarrierCodeRepository.findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc("COUPANG"))
                .willReturn(List.of(
                        PlatformCarrierCode.builder().id(2L).carrier(lotte)
                                .platform("COUPANG").deliveryCompanyCode("HYUNDAI").build()));

        List<CarrierOption> options = carrierCodeService.findOptions("COUPANG");

        // 등록분이 먼저 오고, 표시 이름은 쿠팡 표를 따른다(로컬 표기와 달라도 코드와 어긋나지 않게).
        assertThat(options.get(0)).isEqualTo(new CarrierOption("HYUNDAI", "롯데택배", true));
        // 나머지는 쿠팡이 받아주는 코드 전량 — 등록하지 않은 택배사도 고를 수 있어야 한다.
        assertThat(options).hasSize(CoupangCourierCodes.all().size());
        assertThat(options).contains(new CarrierOption("CJGLS", "CJ대한통운", false));
        assertThat(options.stream().filter(o -> "HYUNDAI".equals(o.deliveryCompanyCode())).count()).isEqualTo(1);
    }

    @Test
    void findOptions_비쿠팡은_등록된것만() {
        Carrier cj = Carrier.builder().id(1L).name("CJ대한통운").isActive(true).build();
        given(platformCarrierCodeRepository.findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc("NAVER"))
                .willReturn(List.of(
                        PlatformCarrierCode.builder().id(1L).carrier(cj)
                                .platform("NAVER").deliveryCompanyCode("CJGLS").build()));

        assertThat(carrierCodeService.findOptions("NAVER"))
                .containsExactly(new CarrierOption("CJGLS", "CJ대한통운", true));
    }

    @Test
    void findOptions_없으면_빈리스트() {
        given(platformCarrierCodeRepository.findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc("NAVER"))
                .willReturn(List.of());

        assertThat(carrierCodeService.findOptions("NAVER")).isEmpty();
    }

    @Test
    void validateDeliveryCompanyCode_쿠팡코드표에_있으면_통과() {
        assertThat(carrierCodeService.validateDeliveryCompanyCode("KDEXP", "COUPANG")).isEqualTo("KDEXP");
    }

    @Test
    void validateDeliveryCompanyCode_쿠팡코드표에_없으면_IllegalArgumentException() {
        assertThatThrownBy(() -> carrierCodeService.validateDeliveryCompanyCode("NOPE", "COUPANG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void validateDeliveryCompanyCode_비쿠팡은_등록된코드만() {
        given(platformCarrierCodeRepository.findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc("NAVER"))
                .willReturn(List.of());

        assertThatThrownBy(() -> carrierCodeService.validateDeliveryCompanyCode("CJGLS", "NAVER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NAVER");
    }
}
