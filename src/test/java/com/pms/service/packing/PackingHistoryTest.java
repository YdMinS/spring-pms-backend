package com.pms.service.packing;

import com.pms.domain.Package;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelStatusLog;
import com.pms.dto.response.PackingHistoryView;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.pms.service.packing.PackingFixtures.INVOICE;
import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** The packing history read (FEATURE_2611_03 / PLAN D29 · D30 · D34 · D39 · D40 · FEATURE_2611_06 / PLAN D9). */
@ExtendWith(MockitoExtension.class)
class PackingHistoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

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

    @Test
    void testHistoryStripsHyphensForInvoiceSideAndMapsRow() {
        ShipmentParcel parcel = PackingFixtures.parcel(PARCEL_ID, PackingFixtures.shipment(), ParcelStatus.UNUSED, 1);
        ShipmentParcelStatusLog row = ShipmentParcelStatusLog.builder().id(3L).shipmentParcel(parcel)
                .fromStatus(ParcelStatus.PENDING).toStatus(ParcelStatus.UNUSED).changedBy("packer@oclyx.com").build();
        // D34 · D40: both KST days inclusive → [from KST 00:00, to + 1 day KST 00:00) in UTC.
        // D39: the order side keeps the keyword as typed.
        given(statusLogRepository.search(LocalDateTime.of(2026, 10, 26, 15, 0), LocalDateTime.of(2026, 11, 2, 15, 0),
                "1234-5678 9012", "123456789012")).willReturn(List.of(row));

        List<PackingHistoryView> views = service.history(DAY.minusDays(6), DAY, " 1234-5678 9012 ");

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.parcelId()).isEqualTo(PARCEL_ID);
            assertThat(view.invoiceNumber()).isEqualTo(INVOICE);
            assertThat(view.carrierName()).isEqualTo("롯데택배");
            assertThat(view.externalOrderId()).isEqualTo("ORD-1");
            assertThat(view.sellerName()).isEqualTo("셀러A");
            assertThat(view.fromStatus()).isEqualTo(ParcelStatus.PENDING);
            assertThat(view.toStatus()).isEqualTo(ParcelStatus.UNUSED);
            assertThat(view.changedBy()).isEqualTo("packer@oclyx.com");
            assertThat(view.boxName()).isNull();
            assertThat(view.boxPackageId()).isNull();
        });
    }

    @Test
    void testHistoryPackedRowCarriesBoxName() {
        ShipmentParcel parcel = PackingFixtures.parcel(PARCEL_ID, PackingFixtures.shipment(), ParcelStatus.PACKED, 1)
                .toBuilder().boxPackage(Package.builder().id(7L).type("3호 상자").build()).build();
        ShipmentParcelStatusLog row = ShipmentParcelStatusLog.builder().id(5L).shipmentParcel(parcel)
                .fromStatus(ParcelStatus.PENDING).toStatus(ParcelStatus.PACKED).changedBy("packer@oclyx.com").build();
        given(statusLogRepository.search(LocalDateTime.of(2026, 11, 1, 15, 0), LocalDateTime.of(2026, 11, 2, 15, 0),
                null, null)).willReturn(List.of(row));

        assertThat(service.history(DAY, DAY, null)).singleElement()
                .satisfies(view -> {
                    assertThat(view.boxName()).isEqualTo("3호 상자");
                    // FEATURE_2611_06 D9: the box id rides along so the change dialog can preselect it.
                    assertThat(view.boxPackageId()).isEqualTo(7L);
                });
    }

    @Test
    void testHistoryBlankKeywordMeansNoKeyword() {
        given(statusLogRepository.search(LocalDateTime.of(2026, 11, 1, 15, 0), LocalDateTime.of(2026, 11, 2, 15, 0),
                null, null)).willReturn(List.of());

        assertThat(service.history(DAY, DAY, "  ")).isEmpty();

        verify(statusLogRepository).search(LocalDateTime.of(2026, 11, 1, 15, 0), LocalDateTime.of(2026, 11, 2, 15, 0),
                null, null);
    }

    @Test
    void testHistoryUsesKstDayBoundsAndReturnsKstTime() {
        ShipmentParcel parcel = PackingFixtures.parcel(PARCEL_ID, PackingFixtures.shipment(), ParcelStatus.UNUSED, 1);
        ShipmentParcelStatusLog row = ShipmentParcelStatusLog.builder().id(4L).shipmentParcel(parcel)
                .fromStatus(ParcelStatus.PENDING).toStatus(ParcelStatus.UNUSED).changedBy("packer@oclyx.com").build();
        // Stored UTC 2026-11-01 16:30 = KST 2026-11-02 01:30 — a KST 00~09 row that belongs to KST Nov 2 (D40).
        ReflectionTestUtils.setField(row, "createdAt", LocalDateTime.of(2026, 11, 1, 16, 30));
        given(statusLogRepository.search(LocalDateTime.of(2026, 11, 1, 15, 0), LocalDateTime.of(2026, 11, 2, 15, 0),
                null, null)).willReturn(List.of(row));

        List<PackingHistoryView> views = service.history(DAY, DAY, null);

        assertThat(views).singleElement()
                .satisfies(view -> assertThat(view.changedAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 1, 30)));
    }
}
