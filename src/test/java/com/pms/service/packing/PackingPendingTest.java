package com.pms.service.packing;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.dto.response.PendingParcelView;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.stock.StockOutService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.pms.service.packing.PackingFixtures.LINE_ID;
import static com.pms.service.packing.PackingFixtures.OTHER_LINE_ID;
import static com.pms.service.packing.PackingFixtures.OTHER_PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.SHIPMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 작업 대상 박스 목록 (FEATURE_2609_40 / PLAN D16 · D31).
 *
 * <p>🔴 동기화 백필은 송장이 붙은 <b>모든 과거 배송 묶음</b>에 {@code PENDING} 박스를 만든다. 상태만 보고
 * 목록을 만들면 첫날부터 이미 끝난 주문으로 가득 찬다 — 기준은 상태가 아니라 잔량이다.
 */
@ExtendWith(MockitoExtension.class)
class PackingPendingTest {

    private static final Long BACKFILL_SHIPMENT_ID = 501L;

    @Mock private ShipmentParcelRepository parcelRepository;
    @Mock private ShipmentParcelItemRepository parcelItemRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PackageRepository packageRepository;
    @Mock private StockOutService stockOutService;
    @Mock private BoxRecipeService boxRecipeService;
    @Mock private MasterChannelConfigService masterChannelConfigService;

    @InjectMocks private PackingServiceImpl service;

    @Test
    void testPendingExcludesZeroRemainingParcel() {
        OrderShipment working = PackingFixtures.shipment();
        OrderShipment backfilled = PackingFixtures.shipment(BACKFILL_SHIPMENT_ID, "ORD-OLD");

        given(parcelRepository.findByStatusAndSeller(ParcelStatus.PENDING, null)).willReturn(List.of(
                PackingFixtures.parcel(PARCEL_ID, working, ParcelStatus.PENDING, 1),
                PackingFixtures.parcel(OTHER_PARCEL_ID, backfilled, ParcelStatus.PENDING, 1)));

        List<OrderLine> lines = List.of(
                PackingFixtures.line(LINE_ID, working),
                PackingFixtures.line(OTHER_LINE_ID, backfilled));
        given(orderLineRepository.findByOrderShipment_IdIn(anyCollection())).willReturn(lines);
        // 백필 묶음의 라인은 이미 전량 출고됐다(필요 4 = 확인 4) → 잔량 0.
        given(stockOutService.remaining(anyCollection())).willAnswer(invocation ->
                PackingFixtures.echo(invocation.<Collection<OrderLine>>getArgument(0),
                        Map.of(LINE_ID, new int[]{4, 1}, OTHER_LINE_ID, new int[]{4, 4})));
        given(parcelRepository.findByOrderShipment_IdIn(anyCollection())).willReturn(List.of(
                PackingFixtures.parcel(PARCEL_ID, working, ParcelStatus.PENDING, 1),
                PackingFixtures.parcel(OTHER_PARCEL_ID, backfilled, ParcelStatus.PENDING, 1)));

        List<PendingParcelView> views = service.pending(null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).parcelId()).isEqualTo(PARCEL_ID);
        assertThat(views.get(0).remainingQty()).isEqualTo(3);
        assertThat(views.get(0).externalOrderId()).isEqualTo("ORD-1");
        assertThat(views.get(0).sellerName()).isEqualTo("셀러A");
        assertThat(views.get(0).totalParcels()).isEqualTo(1);
    }

    @Test
    void testPendingWithoutParcelsReturnsEmptyList() {
        given(parcelRepository.findByStatusAndSeller(ParcelStatus.PENDING, SHIPMENT_ID)).willReturn(List.of());

        assertThat(service.pending(SHIPMENT_ID)).isEmpty();
    }
}
