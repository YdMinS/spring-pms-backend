package com.pms.service.packing;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.dto.request.BoxCandidateRequest;
import com.pms.dto.response.BarcodeLookupResponse;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.pms.service.packing.PackingFixtures.BARCODE;
import static com.pms.service.packing.PackingFixtures.LINE_ID;
import static com.pms.service.packing.PackingFixtures.OTHER_PRODUCT_ID;
import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.PRODUCT_ID;
import static com.pms.service.packing.PackingFixtures.SHIPMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 바코드 조회(D11 의 오류 경로)와 상자 후보 위임(D22 ~ D25).
 *
 * <p>🔴 바코드 응답은 <b>두 실패를 구분</b>해야 한다: 「등록되지 않은 바코드」(물품을 등록해야 한다)와
 * 「이 주문에 없는 물품」(잘못 집었다)은 작업자가 해야 할 일이 완전히 다르다.
 */
@ExtendWith(MockitoExtension.class)
class PackingLookupTest {

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

    private void givenParcel() {
        given(parcelRepository.findById(PARCEL_ID)).willReturn(
                Optional.of(PackingFixtures.parcel(PARCEL_ID, shipment, ParcelStatus.PENDING, 1)));
    }

    private void givenRemainingProduct(Long productId) {
        given(orderLineRepository.findByOrderShipment_Id(SHIPMENT_ID))
                .willReturn(List.of(PackingFixtures.line(LINE_ID, shipment)));
        given(stockOutService.remaining(anyCollection())).willAnswer(invocation -> {
            Collection<OrderLine> lines = invocation.getArgument(0);
            return lines.stream()
                    .map(line -> PackingFixtures.remaining(line.getId(), productId, 4, 0))
                    .toList();
        });
    }

    @Test
    void testBarcodeNotRegistered() {
        givenParcel();
        given(productRepository.findByBarcodeId("0000")).willReturn(Optional.empty());

        BarcodeLookupResponse response = service.barcode("0000", PARCEL_ID);

        assertThat(response.found()).isFalse();
        assertThat(response.inThisParcel()).isFalse();
        verifyNoInteractions(stockOutService);
    }

    @Test
    void testBarcodeNotInThisParcel() {
        givenParcel();
        given(productRepository.findByBarcodeId(BARCODE))
                .willReturn(Optional.of(PackingFixtures.product(OTHER_PRODUCT_ID, "다른물품", BARCODE)));
        givenRemainingProduct(PRODUCT_ID);   // 이 박스에 남은 것은 다른 물품이다

        BarcodeLookupResponse response = service.barcode(BARCODE, PARCEL_ID);

        assertThat(response.found()).isTrue();
        assertThat(response.inThisParcel()).isFalse();
        assertThat(response.productName()).isEqualTo("다른물품");
    }

    @Test
    void testBarcodeInThisParcel() {
        givenParcel();
        given(productRepository.findByBarcodeId(BARCODE))
                .willReturn(Optional.of(PackingFixtures.product(PRODUCT_ID, "양말A", BARCODE)));
        givenRemainingProduct(PRODUCT_ID);

        BarcodeLookupResponse response = service.barcode(BARCODE, PARCEL_ID);

        assertThat(response.found()).isTrue();
        assertThat(response.inThisParcel()).isTrue();
    }

    @Test
    void testBarcodeRequiresParcelId() {
        // 박스가 없으면 「이 주문에 없는 물품」을 판정할 기준이 없다 — 두 실패를 뭉뚱그리게 된다.
        assertThatThrownBy(() -> service.barcode(BARCODE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("박스를 먼저 스캔해야 합니다");

        verifyNoInteractions(parcelRepository, productRepository, stockOutService);
    }

    @Test
    void testBoxCandidatesDelegatesToRecipe() {
        given(boxRecipeService.candidates(anyList())).willReturn(List.of());

        List<BoxCandidate> candidates = service.boxCandidates(new BoxCandidateRequest(
                List.of(new BoxCandidateRequest.CandidateItem(PRODUCT_ID, 2))));

        // 기억이 없으면 빈 목록 + 200 이다(예외가 아니다). 추천 규칙은 여기에 두 번째로 만들지 않는다.
        assertThat(candidates).isEmpty();
        ArgumentCaptor<List<RecipeItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(boxRecipeService, times(1)).candidates(captor.capture());
        assertThat(captor.getValue()).containsExactly(new RecipeItem(PRODUCT_ID, 2));
    }
}
