package com.pms.service.reservation;

import com.pms.domain.TenantOrderSetting;
import com.pms.dto.request.OrderSettingRequest;
import com.pms.dto.response.OrderSettingResponse;
import com.pms.repository.TenantOrderSettingRepository;
import com.pms.service.coupang.SyncWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderSettingServiceImplTest {

    @Mock private TenantOrderSettingRepository tenantOrderSettingRepository;
    @InjectMocks private OrderSettingServiceImpl service;

    @Test
    void get_returnsDefaultWhenNoRow() {
        given(tenantOrderSettingRepository.findFirstByOrderByIdAsc()).willReturn(Optional.empty());

        OrderSettingResponse response = service.get();

        assertThat(response.reservedShipmentTime()).isEqualTo("00:02");
        assertThat(response.nextExecuteAt().toLocalTime()).isEqualTo(LocalTime.of(0, 2));
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        assertThat(response.nextExecuteAt()).isAfter(now).isBefore(now.plusDays(1).plusMinutes(1));
    }

    @Test
    void update_rejectsMalformedTime() {
        assertThatThrownBy(() -> service.update(new OrderSettingRequest("24:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HH:mm");
    }

    @Test
    void update_createsRowWhenAbsent() {
        given(tenantOrderSettingRepository.findFirstByOrderByIdAsc()).willReturn(Optional.empty());

        service.update(new OrderSettingRequest("23:30"));

        ArgumentCaptor<TenantOrderSetting> saved = ArgumentCaptor.forClass(TenantOrderSetting.class);
        verify(tenantOrderSettingRepository).save(saved.capture());
        assertThat(saved.getValue().getReservedShipmentTime()).isEqualTo("23:30");
    }
}
