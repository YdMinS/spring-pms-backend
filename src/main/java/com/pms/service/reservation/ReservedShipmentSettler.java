package com.pms.service.reservation;

import com.pms.domain.ReservedItemResult;
import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentItem;
import com.pms.domain.ReservedShipmentStatus;
import com.pms.repository.ReservedShipmentItemRepository;
import com.pms.repository.ReservedShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 결과가 전부 끝난 예약을 닫는다 (FEATURE_2609_75). 해제·취소 경로(01·02)가 결과를 바꾼 뒤 부른다.
 *
 * <p>규칙: 남은 결과(PENDING·FAILED)가 없으면 → 완료류(SUCCEEDED·EXTERNAL·CANCELLED)가 하나라도 있으면 DONE, 아니면 CANCELLED.
 * <p>⚠️ RUNNING 은 건드리지 않는다 — 실행기가 스스로 마감한다. 호출자 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class ReservedShipmentSettler {

    private static final Set<ReservedItemResult> OPEN =
            EnumSet.of(ReservedItemResult.PENDING, ReservedItemResult.FAILED);
    private static final Set<ReservedItemResult> DONE_RESULTS =
            EnumSet.of(ReservedItemResult.SUCCEEDED, ReservedItemResult.EXTERNAL, ReservedItemResult.CANCELLED);
    private static final Set<ReservedShipmentStatus> CLOSABLE =
            EnumSet.of(ReservedShipmentStatus.SCHEDULED, ReservedShipmentStatus.STOPPED);

    private final ReservedShipmentRepository reservedShipmentRepository;
    private final ReservedShipmentItemRepository reservedShipmentItemRepository;

    public void settle(Collection<Long> reservationIds) {
        for (Long id : new LinkedHashSet<>(reservationIds)) {
            ReservedShipment reservation = reservedShipmentRepository.findScopedById(id).orElse(null);
            if (reservation == null || !CLOSABLE.contains(reservation.getStatus())) {
                continue;
            }
            List<ReservedShipmentItem> items = reservedShipmentItemRepository.findByReservedShipment_Id(id);
            if (items.stream().anyMatch(i -> OPEN.contains(i.getResult()))) {
                continue;
            }
            ReservedShipmentStatus next = items.stream().anyMatch(i -> DONE_RESULTS.contains(i.getResult()))
                    ? ReservedShipmentStatus.DONE : ReservedShipmentStatus.CANCELLED;
            reservedShipmentRepository.save(reservation.toBuilder().status(next).build());
        }
    }
}
