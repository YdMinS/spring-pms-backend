package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Platform;
import com.pms.domain.ShipmentParcel;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.ShipmentParcelRecorder;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 주문 동기화 → 송장번호 백필 테스트 (FEATURE_2609_40 / PLAN D5 ② · D6 · D7).
 *
 * <p>🔴 추가 API 호출 없이 <b>이미 받은 응답</b>의 {@code invoiceNumber}·{@code deliveryCompanyName} 만 쓴다.
 * {@link ShipmentParcelRecorder} 는 진짜 인스턴스를 넣고 리포지토리 목이 저장 행을 쌓아 멱등성을 흉내낸다.
 */
@ExtendWith(MockitoExtension.class)
class OrderUpserterParcelTest {

    private static final String BOX_ID = "700000012345";
    private static final String ORDER_ID = "300000012345";
    private static final String ITEM_ID = "800000012345";
    private static final Long SHIPMENT_ID = 20L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderShipmentRepository orderShipmentRepository;
    @Mock
    private OrderLineRepository orderLineRepository;
    @Mock
    private CoupangOrderLineRepository coupangOrderLineRepository;
    @Mock
    private ProductListingOptionRepository productListingOptionRepository;
    @Mock
    private CarrierCodeService carrierCodeService;
    @Mock
    private ShipmentParcelRepository shipmentParcelRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrderUpserter upserter;
    private MarketplaceAccount account;
    private final List<ShipmentParcel> parcels = new ArrayList<>();

    @BeforeEach
    void setUp() {
        upserter = new OrderUpserter(orderRepository, orderShipmentRepository, orderLineRepository,
                coupangOrderLineRepository, productListingOptionRepository, carrierCodeService,
                new ShipmentParcelRecorder(shipmentParcelRepository));

        account = MarketplaceAccountFixture.coupangStubBuilder("A00012345", null)
                .id(1L).platform(Platform.COUPANG).isActive(true).build();

        lenient().when(orderRepository.save(any(Order.class)))
                .thenAnswer(i -> withId(i.getArgument(0)));
        lenient().when(orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(any(), anyString()))
                .thenReturn(Optional.empty());
        // 배송 묶음은 저장 시 id 를 얻는다 — 박스 저장의 키다.
        lenient().when(orderShipmentRepository.save(any(OrderShipment.class)))
                .thenAnswer(i -> {
                    OrderShipment shipment = i.getArgument(0);
                    return shipment.getId() != null ? shipment : shipment.toBuilder().id(SHIPMENT_ID).build();
                });
        lenient().when(orderShipmentRepository.findByOrder_IdAndExternalShipmentId(any(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(orderLineRepository.save(any(OrderLine.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(coupangOrderLineRepository.save(any(CoupangOrderLine.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(coupangOrderLineRepository
                        .findByMarketplaceAccount_IdAndShipmentBoxIdAndOrderIdRawAndVendorItemId(
                                any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(productListingOptionRepository.findByPlatformOptionId(any()))
                .thenReturn(Optional.empty());

        lenient().when(shipmentParcelRepository.save(any(ShipmentParcel.class)))
                .thenAnswer(i -> {
                    ShipmentParcel parcel = i.getArgument(0);
                    parcels.add(parcel);
                    return parcel;
                });
        lenient().when(shipmentParcelRepository.countByOrderShipment_Id(any()))
                .thenAnswer(i -> parcels.stream()
                        .filter(p -> Objects.equals(p.getOrderShipment().getId(), i.getArgument(0)))
                        .count());
        lenient().when(shipmentParcelRepository.findByOrderShipment_IdAndInvoiceNumber(any(), anyString()))
                .thenAnswer(i -> parcels.stream()
                        .filter(p -> Objects.equals(p.getOrderShipment().getId(), i.getArgument(0))
                                && p.getInvoiceNumber().equals(i.getArgument(1)))
                        .findFirst());
    }

    /** 6. 응답에 송장번호가 있으면 박스 1행이 생기고, 택배사 이름으로 코드가 채워진다. */
    @Test
    void testSyncBackfillsInvoiceFromResponse() {
        given(carrierCodeService.findCodeByName("CJ대한통운", Platform.COUPANG)).willReturn(Optional.of("CJGLS"));

        upserter.upsertBox(account, box(boxWithInvoice("123456789012", "CJ대한통운")));

        assertThat(parcels).hasSize(1);
        ShipmentParcel parcel = parcels.get(0);
        assertThat(parcel.getInvoiceNumber()).isEqualTo("123456789012");
        assertThat(parcel.getCarrierCode()).isEqualTo("CJGLS");
        assertThat(parcel.getCarrierName()).isEqualTo("CJ대한통운");
        assertThat(parcel.getParcelSeq()).isEqualTo(1);
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.PENDING);
        assertThat(parcel.getOrderShipment().getId()).isEqualTo(SHIPMENT_ID);
    }

    /** 7. 🔴 모르는 택배사 이름 → 코드는 NULL, 송장번호는 저장된다(D6 — 스캔에 필요한 건 송장번호 하나다). */
    @Test
    void testSyncKeepsInvoiceWhenCarrierNameUnknown() {
        given(carrierCodeService.findCodeByName("듣보택배", Platform.COUPANG)).willReturn(Optional.empty());

        upserter.upsertBox(account, box(boxWithInvoice("123456789012", "듣보택배")));

        assertThat(parcels).hasSize(1);
        assertThat(parcels.get(0).getCarrierCode()).isNull();
        assertThat(parcels.get(0).getCarrierName()).isEqualTo("듣보택배");
        assertThat(parcels.get(0).getInvoiceNumber()).isEqualTo("123456789012");
    }

    /** 8. 동기화는 반복 실행된다 — 같은 응답을 두 번 먹여도 박스는 1행이다(D7). */
    @Test
    void testSyncIsIdempotent() {
        given(carrierCodeService.findCodeByName(anyString(), any())).willReturn(Optional.of("CJGLS"));
        JsonNode box = box(boxWithInvoice("123456789012", "CJ대한통운"));

        upserter.upsertBox(account, box);
        upserter.upsertBox(account, box);

        assertThat(parcels).hasSize(1);
    }

    /** 9. 🔴 이미 {@code PACKED} 인 박스는 갱신되지 않는다 — 동기화가 포장 결과를 덮으면 원장과 화면이 갈라진다. */
    @Test
    void testSyncDoesNotTouchPackedParcel() {
        parcels.add(ShipmentParcel.builder()
                .orderShipment(OrderShipment.builder().id(SHIPMENT_ID).externalShipmentId(BOX_ID).build())
                .invoiceNumber("123456789012").parcelSeq(1).status(ParcelStatus.PACKED)
                .carrierCode("HANJIN").build());

        upserter.upsertBox(account, box(boxWithInvoice("123456789012", "CJ대한통운")));

        verify(shipmentParcelRepository, never()).save(any());
        assertThat(parcels).hasSize(1);
        assertThat(parcels.get(0).getStatus()).isEqualTo(ParcelStatus.PACKED);
        assertThat(parcels.get(0).getCarrierCode()).isEqualTo("HANJIN");
    }

    /** 10. 백필이 던져도 주문·라인 저장은 성공한다(best-effort). */
    @Test
    void testSyncBackfillFailureDoesNotBreakSync() {
        given(shipmentParcelRepository.findByOrderShipment_IdAndInvoiceNumber(any(), anyString()))
                .willThrow(new RuntimeException("parcel store down"));
        given(carrierCodeService.findCodeByName(anyString(), any())).willReturn(Optional.of("CJGLS"));

        UpsertCount result = upserter.upsertBox(account, box(boxWithInvoice("123456789012", "CJ대한통운")));

        assertThat(result.newCount()).isEqualTo(1);
        verify(orderLineRepository, atLeastOnce()).save(any(OrderLine.class));
        verify(coupangOrderLineRepository, atLeastOnce()).save(any(CoupangOrderLine.class));
        assertThat(parcels).isEmpty();
    }

    /** 송장이 없는 박스는 아무것도 만들지 않는다(아직 발송 전). */
    @Test
    void testSyncCreatesNothingWhenInvoiceMissing() {
        upserter.upsertBox(account, box(boxWithoutInvoice()));

        assertThat(parcels).isEmpty();
        verify(shipmentParcelRepository, never()).save(any());
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private Order withId(Order order) {
        return order.getId() != null ? order : order.toBuilder().id(10L).build();
    }

    private JsonNode box(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String boxWithInvoice(String invoiceNumber, String carrierName) {
        return """
            {"orderId":"%s","shipmentBoxId":"%s","status":"DEPARTURE",
             "invoiceNumber":"%s","deliveryCompanyName":"%s",
             "orderItems":[{"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":1}]}
            """.formatted(ORDER_ID, BOX_ID, invoiceNumber, carrierName, ITEM_ID);
    }

    private String boxWithoutInvoice() {
        return """
            {"orderId":"%s","shipmentBoxId":"%s","status":"INSTRUCT",
             "orderItems":[{"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":1}]}
            """.formatted(ORDER_ID, BOX_ID, ITEM_ID);
    }
}
