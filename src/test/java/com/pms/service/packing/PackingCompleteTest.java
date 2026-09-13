package com.pms.service.packing;

import com.pms.domain.BoxKind;
import com.pms.domain.CarrierRate;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Package;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.dto.request.OutboundConfirmRequest;
import com.pms.dto.request.ParcelCompleteRequest;
import com.pms.dto.response.ParcelCompleteResponse;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.stock.RemainingLine;
import com.pms.service.stock.StockOutService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.pms.service.packing.PackingFixtures.BOX_ID;
import static com.pms.service.packing.PackingFixtures.LINE_ID;
import static com.pms.service.packing.PackingFixtures.OTHER_LINE_ID;
import static com.pms.service.packing.PackingFixtures.OTHER_PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.PRODUCT_ID;
import static com.pms.service.packing.PackingFixtures.SHIPMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [이 박스 완료] (FEATURE_2609_40 / PLAN D4 · D13 · D15 · D24 · D29).
 *
 * <p>지키는 것: 라인마다 출고 1회(D29) · 두 번 눌러도 한 번(D15) · 마지막 박스는 전량(D13) ·
 * 절약은 비율대로, 못 구하면 NULL(D4) · 상자 기억 실패가 출고를 되돌리지 않는다(D24).
 */
@ExtendWith(MockitoExtension.class)
class PackingCompleteTest {

    private static final LocalDate MOVED_ON = LocalDate.of(2026, 9, 13);

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

    // --- fixtures ---

    private static Package box() {
        return Package.builder().id(BOX_ID).type("소형박스").cost(new BigDecimal("300"))
                .boxKind(BoxKind.RECYCLED).build();
    }

    private static ParcelCompleteRequest request(ParcelCompleteRequest.PackedItem... items) {
        return new ParcelCompleteRequest(BOX_ID, List.of(items), MOVED_ON);
    }

    private static ParcelCompleteRequest.PackedItem item(Long lineId, int quantity) {
        return new ParcelCompleteRequest.PackedItem(lineId, PRODUCT_ID, quantity);
    }

    private void givenParcel() {
        given(parcelRepository.findById(PARCEL_ID)).willReturn(
                Optional.of(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1)));
    }

    /** 형제 박스 구성: 잔량 있는 PENDING 이 이것 하나뿐이면 「마지막 박스」라 전량을 요구한다(D13). */
    private void givenSiblings(ParcelStatus... statuses) {
        List<ShipmentParcel> siblings = new java.util.ArrayList<>();
        siblings.add(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        int seq = 2;
        for (ParcelStatus status : statuses) {
            siblings.add(PackingFixtures.parcel(OTHER_PARCEL_ID, shipment, status, seq++));
        }
        given(parcelRepository.findByOrderShipment_IdOrderByParcelSeqAsc(SHIPMENT_ID)).willReturn(siblings);
    }

    private void givenLines(List<OrderLine> lines, Map<Long, int[]> remaining) {
        given(orderLineRepository.findByOrderShipment_Id(SHIPMENT_ID)).willReturn(lines);
        given(stockOutService.remaining(anyCollection())).willAnswer(invocation ->
                PackingFixtures.echo(invocation.<Collection<OrderLine>>getArgument(0), remaining));
    }

    private void givenSaves() {
        given(packageRepository.findById(BOX_ID)).willReturn(Optional.of(box()));
        given(productRepository.findAllById(anyCollection()))
                .willReturn(List.of(PackingFixtures.product(PRODUCT_ID, "양말A", "8801234567890")));
        given(parcelRepository.save(any(ShipmentParcel.class))).willAnswer(inv -> inv.getArgument(0));
    }

    // --- tests ---

    @Test
    void testCompleteCallsStockOutPerOrderLine() {
        givenParcel();
        givenSiblings(ParcelStatus.PENDING);
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment),
                        PackingFixtures.line(OTHER_LINE_ID, shipment)),
                Map.of(LINE_ID, new int[]{2, 0}, OTHER_LINE_ID, new int[]{2, 0}));
        givenSaves();

        ParcelCompleteResponse response =
                service.complete(PARCEL_ID, request(item(LINE_ID, 2), item(OTHER_LINE_ID, 2)));

        ArgumentCaptor<OutboundConfirmRequest> captor = ArgumentCaptor.forClass(OutboundConfirmRequest.class);
        verify(stockOutService, times(2)).confirm(captor.capture());
        assertThat(captor.getAllValues()).extracting(OutboundConfirmRequest::orderLineId)
                .containsExactlyInAnyOrder(LINE_ID, OTHER_LINE_ID);
        assertThat(captor.getAllValues()).allMatch(r -> r.movedOn().equals(MOVED_ON));
        assertThat(response.status()).isEqualTo(ParcelStatus.PACKED);
        assertThat(response.packedQty()).isEqualTo(4);
        verify(parcelItemRepository, times(2)).save(any(ShipmentParcelItem.class));
    }

    @Test
    void testCompleteIsIdempotent() {
        ShipmentParcel pending = PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1);
        ShipmentParcel packed = pending.toBuilder().status(ParcelStatus.PACKED).build();
        given(parcelRepository.findById(PARCEL_ID))
                .willReturn(Optional.of(pending), Optional.of(packed));
        given(parcelRepository.findByOrderShipment_IdOrderByParcelSeqAsc(SHIPMENT_ID))
                .willReturn(List.of(pending));
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), Map.of(LINE_ID, new int[]{2, 0}));
        givenSaves();
        given(parcelItemRepository.findByShipmentParcel_Id(PARCEL_ID))
                .willReturn(List.of(ShipmentParcelItem.builder().id(1L).quantity(2).build()));

        service.complete(PARCEL_ID, request(item(LINE_ID, 2)));
        // 스캐너 Enter 가 두 번 들어간 상황 — 재고가 두 번 빠지면 안 된다.
        ParcelCompleteResponse second = service.complete(PARCEL_ID, request(item(LINE_ID, 2)));

        assertThat(second.status()).isEqualTo(ParcelStatus.PACKED);
        assertThat(second.packedQty()).isEqualTo(2);
        verify(stockOutService, times(1)).confirm(any());
        verify(parcelItemRepository, times(1)).save(any(ShipmentParcelItem.class));
    }

    @Test
    void testLastParcelRequiresAllRemaining() {
        givenParcel();
        givenSiblings(ParcelStatus.PACKED);   // 작업 대상 박스가 이것 하나 = 마지막 박스
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), Map.of(LINE_ID, new int[]{4, 0}));

        assertThatThrownBy(() -> service.complete(PARCEL_ID, request(item(LINE_ID, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("남은 물품을 모두 담아야 합니다");

        // 🔴 검증이 걸리면 아무것도 저장되지 않는다.
        verify(parcelItemRepository, never()).save(any());
        verify(stockOutService, never()).confirm(any());
        verify(parcelRepository, never()).save(any());
        verify(boxRecipeService, never()).remember(anyList(), any());
    }

    @Test
    void testNonLastParcelAllowsPartial() {
        givenParcel();
        givenSiblings(ParcelStatus.PENDING);  // 잔량 있는 PENDING 박스가 둘 → 부분 포장 허용
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), Map.of(LINE_ID, new int[]{4, 0}));
        givenSaves();

        ParcelCompleteResponse response = service.complete(PARCEL_ID, request(item(LINE_ID, 2)));

        assertThat(response.packedQty()).isEqualTo(2);
        verify(stockOutService, times(1)).confirm(any());
    }

    @Test
    void testCompleteRecordsSavings() {
        givenParcel();
        givenSiblings(ParcelStatus.PENDING);
        // 주문수량 2 · 필요 물품 4 개 중 2 개를 이 박스에 담았다 → 비율 0.5 → 상자비 1000 × 2 × 0.5
        givenLines(List.of(lineWithOption(LINE_ID, 2)), Map.of(LINE_ID, new int[]{4, 0}));
        givenSaves();
        given(masterChannelConfigService.resolvePackage(any(), any()))
                .willReturn(Package.builder().id(99L).cost(new BigDecimal("1000")).build());
        given(masterChannelConfigService.resolveDelivery(any(), any()))
                .willReturn(CarrierRate.builder().id(98L).cost(new BigDecimal("3000")).build());

        service.complete(PARCEL_ID, request(item(LINE_ID, 2)));

        ArgumentCaptor<ShipmentParcel> captor = ArgumentCaptor.forClass(ShipmentParcel.class);
        verify(parcelRepository).save(captor.capture());
        ShipmentParcel saved = captor.getValue();
        assertThat(saved.getExpectedBoxCost()).isEqualByComparingTo("1000.00");
        assertThat(saved.getExpectedDeliveryCost()).isEqualByComparingTo("3000.00");
        // 재활용 상자를 썼으므로 실제 상자비는 그 상자의 원가 그대로다(절약액은 저장하지 않는다).
        assertThat(saved.getActualBoxCost()).isEqualByComparingTo("300");
        assertThat(saved.getPackedAt()).isNotNull();
    }

    @Test
    void testCompleteKeepsSavingsNullWhenConfigMissing() {
        givenParcel();
        givenSiblings(ParcelStatus.PENDING);
        givenLines(List.of(lineWithOption(LINE_ID, 2)), Map.of(LINE_ID, new int[]{4, 0}));
        givenSaves();
        // resolver 는 설정이 없으면 400 을 던진다 — 집계가 비는 것이 출고를 막는 것보다 낫다.
        willThrow(new IllegalArgumentException("박스 미설정"))
                .given(masterChannelConfigService).resolvePackage(any(), any());

        ParcelCompleteResponse response = service.complete(PARCEL_ID, request(item(LINE_ID, 2)));

        assertThat(response.status()).isEqualTo(ParcelStatus.PACKED);
        assertThat(response.expectedBoxCost()).isNull();
        assertThat(response.expectedDeliveryCost()).isNull();
        assertThat(response.actualBoxCost()).isEqualByComparingTo("300");
        verify(stockOutService, times(1)).confirm(any());
    }

    @Test
    void testCompleteRemembersRecipe() {
        givenParcel();
        givenSiblings(ParcelStatus.PENDING);
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), Map.of(LINE_ID, new int[]{4, 0}));
        givenSaves();

        service.complete(PARCEL_ID, request(item(LINE_ID, 2)));

        ArgumentCaptor<List<RecipeItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(boxRecipeService, times(1)).remember(captor.capture(), eq(BOX_ID));
        assertThat(captor.getValue()).containsExactly(new RecipeItem(PRODUCT_ID, 2));
    }

    @Test
    void testRecipeFailureDoesNotRollbackStockOut() {
        givenParcel();
        givenSiblings(ParcelStatus.PENDING);
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), Map.of(LINE_ID, new int[]{4, 0}));
        givenSaves();
        willThrow(new IllegalStateException("recipe down"))
                .given(boxRecipeService).remember(anyList(), eq(BOX_ID));

        ParcelCompleteResponse response = service.complete(PARCEL_ID, request(item(LINE_ID, 2)));

        // 기억은 기억일 뿐이다 — 실물은 이미 나갔으므로 출고를 되돌리지 않는다.
        assertThat(response.status()).isEqualTo(ParcelStatus.PACKED);
        verify(stockOutService, times(1)).confirm(any());
        verify(parcelRepository).save(any(ShipmentParcel.class));
    }

    @Test
    void testCompleteRejectsUnexpandedLine() {
        givenParcel();
        given(orderLineRepository.findByOrderShipment_Id(SHIPMENT_ID))
                .willReturn(List.of(PackingFixtures.line(LINE_ID, shipment)));
        List<RemainingLine> unexpanded = List.of(PackingFixtures.unexpanded(LINE_ID));
        given(stockOutService.remaining(anyCollection())).willReturn(unexpanded);

        assertThatThrownBy(() -> service.complete(PARCEL_ID, request(item(LINE_ID, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("구성 물품을 전개할 수 없는 주문입니다");

        verify(parcelItemRepository, never()).save(any());
        verify(stockOutService, never()).confirm(any());
    }

    @Test
    void testCompleteRejectsParcelWithNothingLeft() {
        givenParcel();
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), Map.of(LINE_ID, new int[]{4, 4}));

        assertThatThrownBy(() -> service.complete(PARCEL_ID, request(item(LINE_ID, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("사용하지 않은 박스로 닫으세요");

        verify(stockOutService, never()).confirm(any());
    }

    @Test
    void testCompleteRejectsQuantityOverRemaining() {
        givenParcel();
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), Map.of(LINE_ID, new int[]{4, 3}));

        assertThatThrownBy(() -> service.complete(PARCEL_ID, request(item(LINE_ID, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("남은 수량을 초과했습니다");

        verify(stockOutService, never()).confirm(any());
    }

    /** 절약 계산에 필요한 채널 옵션을 단 라인 — 계산상 상자비·택배비는 이 옵션에서 해석된다. */
    private OrderLine lineWithOption(Long lineId, int orderQty) {
        ProductListing cell = ProductListing.builder().id(70L).build();
        ProductListingOption option = ProductListingOption.builder().id(71L).productListing(cell).build();
        return PackingFixtures.line(lineId, shipment, OrderStatus.SHIPPED, orderQty, 0)
                .toBuilder().productListingOption(option).build();
    }
}
