package com.pms.service.packing;

import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelStatusLog;
import com.pms.repository.ShipmentParcelStatusLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.pms.service.packing.PackingFixtures.PARCEL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The only writer of {@code shipment_parcel_status_log} (FEATURE_2611_03 / PLAN D15 · D16 · FEATURE_2611_06 / PLAN D14). */
@ExtendWith(MockitoExtension.class)
class ParcelStatusRecorderTest {

    @Mock private ShipmentParcelStatusLogRepository statusLogRepository;

    @InjectMocks private ParcelStatusRecorder recorder;

    private final ShipmentParcel parcel =
            PackingFixtures.parcel(PARCEL_ID, PackingFixtures.shipment(), ParcelStatus.UNUSED, 1);

    @Test
    void testRecordWritesOneRowWithBothStatusesAndPerson() {
        recorder.record(parcel, ParcelStatus.PENDING, ParcelStatus.UNUSED, "packer@oclyx.com");

        ArgumentCaptor<ShipmentParcelStatusLog> captor = ArgumentCaptor.forClass(ShipmentParcelStatusLog.class);
        verify(statusLogRepository).save(captor.capture());
        assertThat(captor.getValue().getShipmentParcel()).isSameAs(parcel);
        assertThat(captor.getValue().getFromStatus()).isEqualTo(ParcelStatus.PENDING);
        assertThat(captor.getValue().getToStatus()).isEqualTo(ParcelStatus.UNUSED);
        assertThat(captor.getValue().getChangedBy()).isEqualTo("packer@oclyx.com");
    }

    @Test
    void testRecordSkipsWhenStatusDidNotMove() {
        recorder.record(parcel, ParcelStatus.UNUSED, ParcelStatus.UNUSED, "packer@oclyx.com");

        verifyNoInteractions(statusLogRepository);
    }

    @Test
    void testRecordBoxChangeWritesPackedToPackedRow() {
        ShipmentParcel packed = PackingFixtures.parcel(PARCEL_ID, PackingFixtures.shipment(), ParcelStatus.PACKED, 1);

        recorder.recordBoxChange(packed, "packer@oclyx.com");

        ArgumentCaptor<ShipmentParcelStatusLog> captor = ArgumentCaptor.forClass(ShipmentParcelStatusLog.class);
        verify(statusLogRepository).save(captor.capture());
        assertThat(captor.getValue().getShipmentParcel()).isSameAs(packed);
        assertThat(captor.getValue().getFromStatus()).isEqualTo(ParcelStatus.PACKED);
        assertThat(captor.getValue().getToStatus()).isEqualTo(ParcelStatus.PACKED);
        assertThat(captor.getValue().getChangedBy()).isEqualTo("packer@oclyx.com");
    }
}
