package com.pms.service.packing;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.dto.response.ParcelCloseResponse;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.repository.ShipmentParcelStatusLogRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.stock.StockOutService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.pms.service.packing.PackingFixtures.LINE_ID;
import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.SHIPMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Restoring an unused box (FEATURE_2611_03 / PLAN D1 · D3 · D7 · D11 · D14 · D16 · D20).
 *
 * <p>🔴 Only status · packedAt · packedBy go back. Stock out, box recipe and savings were never written by the
 * unused path, so nothing else is touched.
 */
@ExtendWith(MockitoExtension.class)
class PackingRestoreTest {

    @Mock private ShipmentParcelRepository parcelRepository;
    @Mock private ShipmentParcelItemRepository parcelItemRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PackageRepository packageRepository;
    @Mock private StockOutService stockOutService;
    @Mock private BoxRecipeService boxRecipeService;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private ParcelStatusRecorder parcelStatusRecorder;
    @Mock private ShipmentParcelStatusLogRepository statusLogRepository;

    @InjectMocks private PackingServiceImpl service;

    private final OrderShipment shipment = PackingFixtures.shipment();

    private void givenParcel(ParcelStatus status) {
        ShipmentParcel parcel = PackingFixtures.parcel(PARCEL_ID, shipment, status, 1).toBuilder()
                .packedAt(LocalDateTime.of(2026, 11, 2, 9, 30))
                .packedBy("packer@oclyx.com")
                .build();
        given(parcelRepository.findById(PARCEL_ID)).willReturn(Optional.of(parcel));
    }

    /** The bundle still has {@code required − confirmed} items left to pack. */
    private void givenRemaining(int required, int confirmed) {
        given(orderLineRepository.findByOrderShipment_Id(SHIPMENT_ID))
                .willReturn(List.of(PackingFixtures.line(LINE_ID, shipment)));
        given(stockOutService.remaining(anyCollection())).willAnswer(invocation ->
                PackingFixtures.echo(invocation.<Collection<OrderLine>>getArgument(0), required, confirmed));
    }

    @Test
    void testRestoreReopensUnusedParcelAndRecordsHistory() {
        givenParcel(ParcelStatus.UNUSED);
        givenRemaining(2, 0);
        given(parcelRepository.save(any(ShipmentParcel.class))).willAnswer(inv -> inv.getArgument(0));

        ParcelCloseResponse response = service.restore(PARCEL_ID);

        assertThat(response.parcelId()).isEqualTo(PARCEL_ID);
        assertThat(response.status()).isEqualTo(ParcelStatus.PENDING);
        ArgumentCaptor<ShipmentParcel> captor = ArgumentCaptor.forClass(ShipmentParcel.class);
        verify(parcelRepository).save(captor.capture());
        // D3: the time and the person of the unused close are cleared — the history row keeps them (D33).
        assertThat(captor.getValue().getStatus()).isEqualTo(ParcelStatus.PENDING);
        assertThat(captor.getValue().getPackedAt()).isNull();
        assertThat(captor.getValue().getPackedBy()).isNull();
        verify(parcelStatusRecorder).record(eq(captor.getValue()), eq(ParcelStatus.UNUSED),
                eq(ParcelStatus.PENDING), any());
        verifyNoInteractions(boxRecipeService, parcelItemRepository);
    }

    @Test
    void testRestoreOnPendingParcelIsIdempotent() {
        givenParcel(ParcelStatus.PENDING);

        ParcelCloseResponse response = service.restore(PARCEL_ID);

        assertThat(response.status()).isEqualTo(ParcelStatus.PENDING);
        verify(parcelRepository, never()).save(any());
        verifyNoInteractions(parcelStatusRecorder);
    }

    @Test
    void testRestoreRejectedOnPackedParcel() {
        givenParcel(ParcelStatus.PACKED);

        assertThatThrownBy(() -> service.restore(PARCEL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 출고된 박스입니다");

        verify(parcelRepository, never()).save(any());
        verifyNoInteractions(parcelStatusRecorder);
    }

    @Test
    void testRestoreRejectedWhenNothingLeftToPack() {
        givenParcel(ParcelStatus.UNUSED);
        givenRemaining(2, 2);

        assertThatThrownBy(() -> service.restore(PARCEL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("담을 물품이 없어 되돌릴 수 없습니다 — 이 주문은 이미 모두 출고됐거나 취소됐습니다");

        // D7: the box stays unused.
        verify(parcelRepository, never()).save(any());
        verifyNoInteractions(parcelStatusRecorder);
    }
}
