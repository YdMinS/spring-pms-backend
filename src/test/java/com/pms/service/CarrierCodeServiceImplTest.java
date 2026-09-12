package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.domain.CarrierCatalog;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCarrierCode;
import com.pms.repository.CarrierCatalogRepository;
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

    @Mock
    private CarrierCatalogRepository carrierCatalogRepository;

    @InjectMocks
    private CarrierCodeServiceImpl carrierCodeService;

    @Test
    void resolve_happy() {
        Carrier carrier = Carrier.builder().id(1L).name("CJ대한통운").isActive(true).build();
        PlatformCarrierCode code = PlatformCarrierCode.builder()
                .id(1L).carrier(carrier).platform(Platform.COUPANG).deliveryCompanyCode("CJGLS").build();
        given(carrierRepository.findByIsActiveTrueOrderByIdAsc()).willReturn(List.of(carrier));
        given(platformCarrierCodeRepository.findByCarrier_IdAndPlatform(1L, Platform.COUPANG))
                .willReturn(Optional.of(code));

        assertThat(carrierCodeService.resolveDeliveryCompanyCode(Platform.COUPANG)).isEqualTo("CJGLS");
    }

    @Test
    void resolve_noCode() {
        Carrier carrier = Carrier.builder().id(1L).name("CJ대한통운").isActive(true).build();
        given(carrierRepository.findByIsActiveTrueOrderByIdAsc()).willReturn(List.of(carrier));
        given(platformCarrierCodeRepository.findByCarrier_IdAndPlatform(1L, Platform.COUPANG))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> carrierCodeService.resolveDeliveryCompanyCode(Platform.COUPANG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COUPANG");
    }

    @Test
    void resolve_noActiveCarrier() {
        given(carrierRepository.findByIsActiveTrueOrderByIdAsc()).willReturn(List.of());

        assertThatThrownBy(() -> carrierCodeService.resolveDeliveryCompanyCode(Platform.COUPANG))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- findOptions: 카탈로그가 원천 (PLAN 2609_37 D1 · D4 · D13) ----

    @Test
    void findOptions_catalogOrderRegisteredFirst() {
        givenCatalog(Platform.COUPANG,
                catalog("CJGLS", "CJ대한통운", 10),
                catalog("HANJIN", "한진택배", 20),
                catalog("HYUNDAI", "롯데택배", 30));
        givenRegistered(Platform.COUPANG, registered(Platform.COUPANG, 2L, "롯데택배", "HYUNDAI"));

        List<CarrierOption> options = carrierCodeService.findOptions(Platform.COUPANG);

        // 등록분이 맨 앞, 나머지는 display_order 순.
        assertThat(options).containsExactly(
                new CarrierOption("HYUNDAI", "롯데택배", true),
                new CarrierOption("CJGLS", "CJ대한통운", false),
                new CarrierOption("HANJIN", "한진택배", false));
    }

    @Test
    void findOptions_usesCatalogName() {
        givenCatalog(Platform.COUPANG, catalog("HYUNDAI", "롯데택배", 30));
        givenRegistered(Platform.COUPANG, registered(Platform.COUPANG, 2L, "롯데(로컬 표기)", "HYUNDAI"));

        assertThat(carrierCodeService.findOptions(Platform.COUPANG))
                .containsExactly(new CarrierOption("HYUNDAI", "롯데택배", true));
    }

    @Test
    void findOptions_keepsRegisteredCodeMissingFromCatalog() {
        givenCatalog(Platform.COUPANG, catalog("CJGLS", "CJ대한통운", 10));
        givenRegistered(Platform.COUPANG, registered(Platform.COUPANG, 3L, "옛 택배사", "OLDCODE"));

        // 카탈로그에 없는 등록 코드는 사라지지 않고 등록분 끝에 로컬 이름으로 남는다.
        assertThat(carrierCodeService.findOptions(Platform.COUPANG)).containsExactly(
                new CarrierOption("OLDCODE", "옛 택배사", true),
                new CarrierOption("CJGLS", "CJ대한통운", false));
    }

    @Test
    void findOptions_emptyCatalogFallsBackToRegistered() {
        givenCatalog(Platform.NAVER);
        givenRegistered(Platform.NAVER, registered(Platform.NAVER, 1L, "CJ대한통운", "CJGLS"));

        assertThat(carrierCodeService.findOptions(Platform.NAVER))
                .containsExactly(new CarrierOption("CJGLS", "CJ대한통운", true));
    }

    @Test
    void findOptions_없으면_빈리스트() {
        givenCatalog(Platform.NAVER);
        givenRegistered(Platform.NAVER);

        assertThat(carrierCodeService.findOptions(Platform.NAVER)).isEmpty();
    }

    // ---- validate: 카탈로그가 화이트리스트 ----

    @Test
    void validate_allowsCatalogCode() {
        given(carrierCatalogRepository.existsByPlatform(Platform.COUPANG)).willReturn(true);
        given(carrierCatalogRepository.existsByPlatformAndCode(Platform.COUPANG, "KDEXP")).willReturn(true);

        assertThat(carrierCodeService.validateDeliveryCompanyCode("KDEXP", Platform.COUPANG)).isEqualTo("KDEXP");
    }

    @Test
    void validate_rejectsUnknownCode() {
        given(carrierCatalogRepository.existsByPlatform(Platform.COUPANG)).willReturn(true);
        given(carrierCatalogRepository.existsByPlatformAndCode(Platform.COUPANG, "NOPE")).willReturn(false);

        assertThatThrownBy(() -> carrierCodeService.validateDeliveryCompanyCode("NOPE", Platform.COUPANG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void validate_emptyCatalogAllowsOnlyRegistered() {
        given(carrierCatalogRepository.existsByPlatform(Platform.NAVER)).willReturn(false);
        givenRegistered(Platform.NAVER, registered(Platform.NAVER, 1L, "CJ대한통운", "CJGLS"));

        assertThat(carrierCodeService.validateDeliveryCompanyCode("CJGLS", Platform.NAVER)).isEqualTo("CJGLS");
        assertThatThrownBy(() -> carrierCodeService.validateDeliveryCompanyCode("HANJIN", Platform.NAVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NAVER");
    }

    @Test
    void validate_빈값이면_IllegalArgumentException() {
        assertThatThrownBy(() -> carrierCodeService.validateDeliveryCompanyCode("  ", Platform.COUPANG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("택배사를 선택하세요");
    }

    // ---- fixtures ----

    private void givenCatalog(Platform platform, CarrierCatalog... rows) {
        given(carrierCatalogRepository.findByPlatformOrderByDisplayOrderAscCodeAsc(platform))
                .willReturn(List.of(rows));
    }

    private void givenRegistered(Platform platform, PlatformCarrierCode... codes) {
        given(platformCarrierCodeRepository.findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc(platform))
                .willReturn(List.of(codes));
    }

    private CarrierCatalog catalog(String code, String name, int displayOrder) {
        return CarrierCatalog.builder()
                .platform(Platform.COUPANG).code(code).name(name).displayOrder(displayOrder).build();
    }

    private PlatformCarrierCode registered(Platform platform, Long carrierId, String carrierName, String code) {
        Carrier carrier = Carrier.builder().id(carrierId).name(carrierName).isActive(true).build();
        return PlatformCarrierCode.builder()
                .id(carrierId).carrier(carrier).platform(platform).deliveryCompanyCode(code).build();
    }
}
