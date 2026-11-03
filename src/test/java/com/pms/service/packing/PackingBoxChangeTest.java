package com.pms.service.packing;

import com.pms.domain.BoxKind;
import com.pms.domain.OrderShipment;
import com.pms.domain.Package;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.dto.response.ParcelCompleteResponse;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.pms.service.packing.PackingFixtures.BOX_ID;
import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static com.pms.service.packing.PackingFixtures.PRODUCT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Changing the box of a packed parcel (FEATURE_2611_06 / PLAN D1 · D4 · D5 · D6 · D7 · D12 · D14).
 *
 * <p>🔴 Only the box and its actual cost move. Stock out, contents, packed time, packer and the two expected costs
 * stay as the complete path wrote them.
 */
@ExtendWith(MockitoExtension.class)
class PackingBoxChangeTest {

    private static final Long NEW_BOX_ID = 8L;
    private static final LocalDateTime PACKED_AT = LocalDateTime.of(2026, 11, 3, 9, 30);

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

    private static Package oldBox() {
        return Package.builder().id(BOX_ID).type("소형박스").cost(new BigDecimal("300"))
                .boxKind(BoxKind.PURCHASED).build();
    }

    private static Package newBox() {
        return Package.builder().id(NEW_BOX_ID).type("중형박스").cost(new BigDecimal("450"))
                .boxKind(BoxKind.PURCHASED).build();
    }

    private ShipmentParcel givenParcel(ParcelStatus status) {
        ShipmentParcel parcel = PackingFixtures.parcel(PARCEL_ID, shipment, status, 1).toBuilder()
                .boxPackage(status == ParcelStatus.PACKED ? oldBox() : null)
                .packedAt(PACKED_AT)
                .packedBy("packer@oclyx.com")
                .expectedBoxCost(new BigDecimal("500.00"))
                .actualBoxCost(new BigDecimal("300"))
                .expectedDeliveryCost(new BigDecimal("3000.00"))
                .build();
        given(parcelRepository.findById(PARCEL_ID)).willReturn(Optional.of(parcel));
        return parcel;
    }

    /** The packed contents: product 33 in two rows (2 + 1) — the recipe merges them into 33:3. */
    private void givenNewBoxAndContents(ShipmentParcel parcel) {
        given(packageRepository.findById(NEW_BOX_ID)).willReturn(Optional.of(newBox()));
        given(parcelRepository.save(any(ShipmentParcel.class))).willAnswer(inv -> inv.getArgument(0));
        given(parcelItemRepository.findByShipmentParcel_Id(PARCEL_ID)).willReturn(List.of(
                ShipmentParcelItem.builder().shipmentParcel(parcel)
                        .product(PackingFixtures.product(PRODUCT_ID, "양말A", "8801234567890")).quantity(2).build(),
                ShipmentParcelItem.builder().shipmentParcel(parcel)
                        .product(PackingFixtures.product(PRODUCT_ID, "양말A", "8801234567890")).quantity(1).build()));
    }

    @Test
    void testChangeBoxSwapsBoxAndActualCostOnly() {
        ShipmentParcel parcel = givenParcel(ParcelStatus.PACKED);
        givenNewBoxAndContents(parcel);

        ParcelCompleteResponse response = service.changeBox(PARCEL_ID, NEW_BOX_ID);

        ArgumentCaptor<ShipmentParcel> captor = ArgumentCaptor.forClass(ShipmentParcel.class);
        verify(parcelRepository).save(captor.capture());
        ShipmentParcel saved = captor.getValue();
        assertThat(saved.getBoxPackage().getId()).isEqualTo(NEW_BOX_ID);
        // D6: the actual box cost is the new box's cost now.
        assertThat(saved.getActualBoxCost()).isEqualByComparingTo("450");
        // D4: everything else stays.
        assertThat(saved.getStatus()).isEqualTo(ParcelStatus.PACKED);
        assertThat(saved.getPackedAt()).isEqualTo(PACKED_AT);
        assertThat(saved.getPackedBy()).isEqualTo("packer@oclyx.com");
        assertThat(saved.getExpectedBoxCost()).isEqualByComparingTo("500.00");
        assertThat(saved.getExpectedDeliveryCost()).isEqualByComparingTo("3000.00");
        assertThat(response.status()).isEqualTo(ParcelStatus.PACKED);
        assertThat(response.packedQty()).isEqualTo(3);
        assertThat(response.notices()).isEmpty();
        assertThat(response.actualBoxCost()).isEqualByComparingTo("450");
        assertThat(response.expectedBoxCost()).isEqualByComparingTo("500.00");
        // No stock out moves and the contents are not rewritten.
        verifyNoInteractions(stockOutService);
        verify(parcelItemRepository, never()).save(any());
        // D5: the memory moves from the old box to the new one, with the merged combination.
        List<RecipeItem> recipe = List.of(new RecipeItem(PRODUCT_ID, 3));
        verify(boxRecipeService).forget(recipe, BOX_ID);
        verify(boxRecipeService).remember(recipe, NEW_BOX_ID);
        // D7 · D14: one box change history row.
        verify(parcelStatusRecorder).recordBoxChange(eq(saved), any());
    }

    @Test
    void testChangeBoxSwallowsRecipeMemoryException() {
        ShipmentParcel parcel = givenParcel(ParcelStatus.PACKED);
        givenNewBoxAndContents(parcel);
        willThrow(new IllegalStateException("db down")).given(boxRecipeService).forget(anyList(), eq(BOX_ID));

        ParcelCompleteResponse response = service.changeBox(PARCEL_ID, NEW_BOX_ID);

        // D5: like completion (2609_40 D24), the exception from the memory is swallowed here.
        assertThat(response.actualBoxCost()).isEqualByComparingTo("450");
        verify(parcelRepository).save(any(ShipmentParcel.class));
        verify(parcelStatusRecorder).recordBoxChange(any(ShipmentParcel.class), any());
    }

    @Test
    void testChangeBoxToSameBoxChangesNothing() {
        givenParcel(ParcelStatus.PACKED);

        ParcelCompleteResponse response = service.changeBox(PARCEL_ID, BOX_ID);

        // D4 · D7: success with the stored values, no memory move, no history row.
        assertThat(response.status()).isEqualTo(ParcelStatus.PACKED);
        assertThat(response.actualBoxCost()).isEqualByComparingTo("300");
        verify(parcelRepository, never()).save(any());
        verifyNoInteractions(boxRecipeService, parcelStatusRecorder);
    }

    @Test
    void testChangeBoxRejectedOnPendingParcel() {
        givenParcel(ParcelStatus.PENDING);

        assertThatThrownBy(() -> service.changeBox(PARCEL_ID, NEW_BOX_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("포장 완료한 박스만 상자를 바꿀 수 있습니다");

        verify(parcelRepository, never()).save(any());
        verifyNoInteractions(boxRecipeService, parcelStatusRecorder);
    }

    @Test
    void testChangeBoxRejectedOnUnusedParcel() {
        givenParcel(ParcelStatus.UNUSED);

        assertThatThrownBy(() -> service.changeBox(PARCEL_ID, NEW_BOX_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("포장 완료한 박스만 상자를 바꿀 수 있습니다");

        verify(parcelRepository, never()).save(any());
        verifyNoInteractions(boxRecipeService, parcelStatusRecorder);
    }

    @Test
    void testChangeBoxRejectedWhenBoxMissing() {
        givenParcel(ParcelStatus.PACKED);
        given(packageRepository.findById(NEW_BOX_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeBox(PARCEL_ID, NEW_BOX_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상자를 찾을 수 없습니다");

        verify(parcelRepository, never()).save(any());
        verifyNoInteractions(boxRecipeService, parcelStatusRecorder);
    }
}
