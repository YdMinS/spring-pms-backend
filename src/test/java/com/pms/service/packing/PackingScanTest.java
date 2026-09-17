package com.pms.service.packing;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.dto.response.OutboundProductLine;
import com.pms.dto.response.PackingRemainingItem;
import com.pms.dto.response.PackingScanResponse;
import com.pms.exception.BusinessException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static com.pms.service.packing.PackingFixtures.BARCODE;
import static com.pms.service.packing.PackingFixtures.INVOICE;
import static com.pms.service.packing.PackingFixtures.LINE_ID;
import static com.pms.service.packing.PackingFixtures.OTHER_LINE_ID;
import static com.pms.service.packing.PackingFixtures.OTHER_PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.OTHER_PRODUCT_ID;
import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.PRODUCT_ID;
import static com.pms.service.packing.PackingFixtures.SHIPMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 송장 스캔 (FEATURE_2609_40 / PLAN D9 ~ D13 · D27 · D28 · D31).
 *
 * <p>여기서 지키는 것 셋:
 * <ul>
 *   <li>주문 상태로 거르지 않는다(D27) — 거르면 스캔한 송장이 전부 빈 목록이 되어 기능이 통째로 죽는다</li>
 *   <li>이미 담긴 수량을 두 번 빼지 않는다 — 완료가 출고를 남기므로 잔량에서 이미 빠져 있다</li>
 *   <li>잔량 0 인 박스는 「마지막 박스」가 아니다(D31) — 백필이 만든 박스에서 전량 검문이 걸리면 안 된다</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PackingScanTest {

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

    private void givenParcel(ShipmentParcel parcel, ShipmentParcel... siblings) {
        given(parcelRepository.findByInvoiceNumberOrderByIdAsc(INVOICE)).willReturn(List.of(parcel));
        given(parcelRepository.findByOrderShipment_IdOrderByParcelSeqAsc(SHIPMENT_ID))
                .willReturn(siblings.length == 0 ? List.of(parcel) : List.of(siblings));
    }

    private void givenLines(List<OrderLine> lines, int required, int confirmed) {
        given(orderLineRepository.findByOrderShipment_Id(SHIPMENT_ID)).willReturn(lines);
        given(stockOutService.remaining(anyCollection())).willAnswer(invocation ->
                PackingFixtures.echo(invocation.<Collection<OrderLine>>getArgument(0), required, confirmed));
        given(productRepository.findAllById(anyCollection()))
                .willReturn(List.of(PackingFixtures.product(PRODUCT_ID, "양말A", BARCODE)));
    }

    /** 한 라인이 물품 2개를 소진하는 경우 — 사진 있는 물품 하나, 없는 물품 하나. */
    private void givenTwoProductLine() {
        given(orderLineRepository.findByOrderShipment_Id(SHIPMENT_ID))
                .willReturn(List.of(PackingFixtures.line(LINE_ID, shipment)));
        given(stockOutService.remaining(anyCollection())).willReturn(List.of(new RemainingLine(LINE_ID,
                List.of(new OutboundProductLine(PRODUCT_ID, "양말A", 2, 0),
                        new OutboundProductLine(OTHER_PRODUCT_ID, "양말B", 1, 0)), null)));
        given(productRepository.findAllById(anyCollection()))
                .willReturn(List.of(PackingFixtures.product(PRODUCT_ID, "양말A", BARCODE, "https://img/a.jpg"),
                        PackingFixtures.product(OTHER_PRODUCT_ID, "양말B", null, null)));
    }

    @Test
    void testScanReturnsLinesRegardlessOfOrderStatus() {
        // 포장 시점의 라인은 이미 배송지시(SHIPPED)다 — 출고 화면의 상태 필터를 가져오면 여기가 비어 버린다.
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment, OrderStatus.SHIPPED, 2, 0)), 4, 0);

        PackingScanResponse response = service.scan(INVOICE);

        assertThat(response.remaining()).hasSize(1);
        assertThat(response.remaining().get(0).remainingQty()).isEqualTo(4);
        assertThat(response.order().externalOrderId()).isEqualTo("ORD-1");
        assertThat(response.parcel().status()).isEqualTo(ParcelStatus.PENDING);
    }

    @Test
    void testScanExcludesFullyCancelledLine() {
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        givenLines(List.of(
                PackingFixtures.line(LINE_ID, shipment, OrderStatus.SHIPPED, 2, 0),
                // 전량 취소 = 주문수량만큼 취소됐다. 상태 컬럼은 그대로라 수량으로 판정한다.
                PackingFixtures.line(OTHER_LINE_ID, shipment, OrderStatus.SHIPPED, 2, 2)), 4, 0);

        PackingScanResponse response = service.scan(INVOICE);

        assertThat(response.remaining()).hasSize(1);
        assertThat(response.remaining().get(0).orderLineId()).isEqualTo(LINE_ID);
    }

    @Test
    void testScanDoesNotDoubleSubtractPackedItems() {
        // 다른 박스가 2개를 담아 완료 = STOCK_OUT −2. 잔량은 4−2=2 여야 한다(4−2−2=0 이 아니다).
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 2),
                PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 2),
                PackingFixtures.parcel(OTHER_PARCEL_ID, shipment, ParcelStatus.PACKED, 1));
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), 4, 2);

        PackingScanResponse response = service.scan(INVOICE);

        assertThat(response.remaining().get(0).remainingQty()).isEqualTo(2);
        // 🔴 박스 내용물을 한 번 더 빼지 않는다는 것을 구조로 못 박는다: 잔량 경로가 그 테이블을 읽지 않는다.
        verifyNoInteractions(parcelItemRepository);
    }

    @Test
    void testScanIncludesBarcode() {
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), 4, 0);

        PackingScanResponse response = service.scan(INVOICE);

        // 이 값이 없으면 화면이 스캔마다 서버를 불러야 한다(D11).
        assertThat(response.remaining().get(0).barcodeId()).isEqualTo(BARCODE);
    }

    @Test
    void testScanOnPackedParcelReturnsStatus() {
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PACKED, 1));

        PackingScanResponse response = service.scan(INVOICE);

        assertThat(response.parcel().status()).isEqualTo(ParcelStatus.PACKED);
        assertThat(response.remaining()).isEmpty();
        assertThat(response.isLastParcel()).isFalse();
        verifyNoInteractions(stockOutService);
    }

    @Test
    void testIsLastParcelIgnoresZeroRemainingParcel() {
        // ① 백필이 만든 박스: PENDING 이지만 이미 다 나간 주문이라 잔량이 0 → 마지막 박스가 아니다.
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), 4, 4);

        assertThat(service.scan(INVOICE).isLastParcel()).isFalse();

        // ② 같은 모양이지만 잔량이 남은 박스: 형제가 모두 닫혔으므로 마지막 박스다(전량을 요구한다).
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 2),
                PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 2),
                PackingFixtures.parcel(OTHER_PARCEL_ID, shipment, ParcelStatus.UNUSED, 1));
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), 4, 0);

        assertThat(service.scan(INVOICE).isLastParcel()).isTrue();
    }

    @Test
    void scanCarriesProductImage() {
        // 사진이 없는 물품도 예외가 아니다 — null 로 내려가고 화면이 회색 자리를 그린다(2609_54/D4).
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        givenTwoProductLine();

        PackingScanResponse response = service.scan(INVOICE);

        assertThat(response.remaining())
                .extracting(PackingRemainingItem::productId, PackingRemainingItem::imageUrl)
                .containsExactly(tuple(PRODUCT_ID, "https://img/a.jpg"), tuple(OTHER_PRODUCT_ID, null));
    }

    @Test
    void scanCarriesCustomerNames() {
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), 4, 0);

        PackingScanResponse response = service.scan(INVOICE);

        // 두 이름을 그대로 싣는다 — 무엇을 보일지는 화면이 정한다(2609_54/D5).
        assertThat(response.order().ordererName()).isEqualTo("김주문");
        assertThat(response.order().receiverName()).isEqualTo("박수취");
    }

    @Test
    void scanCarriesChannel() {
        // 포장 화면은 판매자와 **채널**을 함께 보여준다 — 계정은 판매자와 같은 경로에서 나온다.
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        givenLines(List.of(PackingFixtures.line(LINE_ID, shipment)), 4, 0);

        PackingScanResponse response = service.scan(INVOICE);

        assertThat(response.order().sellerName()).isEqualTo("셀러A");
        assertThat(response.order().platform()).isEqualTo("COUPANG");
        assertThat(response.order().accountAlias()).isEqualTo("본계정");
    }

    @Test
    void scanReadsProductsOnce() {
        // 🔴 이 조각의 핵심 검문 — 사진을 얻으려고 물품별로 읽는 코드가 들어오면 여기서 깨진다.
        givenParcel(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1));
        givenTwoProductLine();

        service.scan(INVOICE);

        verify(productRepository, times(1)).findAllById(anyCollection());
    }

    @Test
    void testScanOnUnknownInvoiceReturnsNotFound() {
        given(parcelRepository.findByInvoiceNumberOrderByIdAsc("nope")).willReturn(List.of());

        assertThatThrownBy(() -> service.scan("nope"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("송장번호를 찾을 수 없습니다");
    }
}
