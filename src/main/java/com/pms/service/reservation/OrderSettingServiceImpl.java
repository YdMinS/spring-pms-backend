package com.pms.service.reservation;

import com.pms.domain.TenantOrderSetting;
import com.pms.dto.request.OrderSettingRequest;
import com.pms.dto.response.OrderSettingResponse;
import com.pms.repository.TenantOrderSettingRepository;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.regex.Pattern;

/**
 * {@link OrderSettingService} 구현. 행이 없으면 기본값 00:02 (D4).
 *
 * <p>🔴 "지금"은 {@code LocalDateTime.now(SyncWindow.KST)} 다 — 서버 기본 시간대(UTC)를 쓰면 9시간 어긋난다(D4).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderSettingServiceImpl implements OrderSettingService {

    public static final String DEFAULT_RESERVED_SHIPMENT_TIME = "00:02";
    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final TenantOrderSettingRepository tenantOrderSettingRepository;

    @Override
    public OrderSettingResponse get() {
        String time = currentTime();
        return new OrderSettingResponse(time, nextExecuteAt(time));
    }

    @Override
    @Transactional
    public OrderSettingResponse update(OrderSettingRequest request) {
        String time = request.reservedShipmentTime();
        if (time == null || !HH_MM.matcher(time).matches()) {
            throw new IllegalArgumentException("예약 시각은 HH:mm 형식(00:00~23:59)이어야 합니다");
        }
        TenantOrderSetting next = tenantOrderSettingRepository.findFirstByOrderByIdAsc()
                .map(s -> s.toBuilder().reservedShipmentTime(time).build())
                .orElseGet(() -> TenantOrderSetting.builder().reservedShipmentTime(time).build());
        tenantOrderSettingRepository.save(next);
        return new OrderSettingResponse(time, nextExecuteAt(time));
    }

    @Override
    public LocalDateTime nextExecuteAt() {
        return nextExecuteAt(currentTime());
    }

    private String currentTime() {
        return tenantOrderSettingRepository.findFirstByOrderByIdAsc()
                .map(TenantOrderSetting::getReservedShipmentTime)
                .orElse(DEFAULT_RESERVED_SHIPMENT_TIME);
    }

    static LocalDateTime nextExecuteAt(String time) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        LocalDateTime candidate = now.toLocalDate().atTime(LocalTime.parse(time));
        return candidate.isAfter(now) ? candidate : candidate.plusDays(1);
    }
}
