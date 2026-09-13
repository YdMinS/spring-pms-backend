package com.pms.service.packing;

import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.dto.response.ParcelCloseResponse;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.stock.StockOutService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 「사용하지 않은 박스」로 닫기 (FEATURE_2609_40 / PLAN D32).
 *
 * <p>택배사가 송장 2장을 발급했는데 실제로 한 박스에 다 담기는 일이 있다 — 그때 남는 박스를 닫는 문이다.
 * 🔴 포장 사실이 아니므로 <b>출고·상자 기억·절약을 하나도 남기지 않는다.</b>
 */
@ExtendWith(MockitoExtension.class)
class PackingUnusedTest {

    @Mock private ShipmentParcelRepository parcelRepository;
    @Mock private ShipmentParcelItemRepository parcelItemRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PackageRepository packageRepository;
    @Mock private StockOutService stockOutService;
    @Mock private BoxRecipeService boxRecipeService;
    @Mock private MasterChannelConfigService masterChannelConfigService;

    @InjectMocks private PackingServiceImpl service;

    private final OrderShipment shipment = PackingFixtures.shipment();

    private void givenParcel(ParcelStatus status) {
        given(parcelRepository.findById(PARCEL_ID))
                .willReturn(Optional.of(PackingFixtures.parcel(PARCEL_ID, shipment, status, 2)));
    }

    @Test
    void testUnusedClosesParcelWithoutStockOut() {
        givenParcel(ParcelStatus.PENDING);
        given(parcelRepository.save(any(ShipmentParcel.class))).willAnswer(inv -> inv.getArgument(0));

        ParcelCloseResponse response = service.unused(PARCEL_ID);

        assertThat(response.status()).isEqualTo(ParcelStatus.UNUSED);
        ArgumentCaptor<ShipmentParcel> captor = ArgumentCaptor.forClass(ShipmentParcel.class);
        verify(parcelRepository).save(captor.capture());
        // 누가 언제 닫았는지는 남긴다 — 되돌리기가 없으므로 흔적이 유일한 근거다.
        assertThat(captor.getValue().getPackedAt()).isNotNull();
        assertThat(captor.getValue().getBoxPackage()).isNull();
        verifyNoInteractions(stockOutService, boxRecipeService, parcelItemRepository);
    }

    @Test
    void testUnusedIsIdempotent() {
        givenParcel(ParcelStatus.UNUSED);

        ParcelCloseResponse response = service.unused(PARCEL_ID);

        assertThat(response.status()).isEqualTo(ParcelStatus.UNUSED);
        verify(parcelRepository, never()).save(any());
    }

    @Test
    void testUnusedRejectedOnPackedParcel() {
        givenParcel(ParcelStatus.PACKED);

        assertThatThrownBy(() -> service.unused(PARCEL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 출고된 박스입니다");

        verify(parcelRepository, never()).save(any());
    }
}
