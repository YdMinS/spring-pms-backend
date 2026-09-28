package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * 예약 발송 1건 = 실행 시각 1개(FEATURE_2609_75 / D5 · D28).
 *
 * <p>🔴 시각 칸 3개는 전부 <b>KST 벽시계</b>다(D4) — {@code LocalDateTime.now(SyncWindow.KST)} 로만 만든다.
 */
@Entity
@Table(name = "reserved_shipment")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ReservedShipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 사용자가 고른 실행 시각(KST). */
    @Column(name = "execute_at", nullable = false)
    private LocalDateTime executeAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservedShipmentStatus status;

    /** 실패로 끝난 실행 횟수. 3 이 되면 자동 재시도를 멈춘다(D16). */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /** 다음 실행 시각(KST). 처음 = executeAt, 실패 뒤 = 그 실행 시작 + 20분(D16). */
    @Column(name = "next_run_at", nullable = false)
    private LocalDateTime nextRunAt;

    /** 첫 실행 계기. null = 아직 실행 전(= 시각 변경·송장 교체 가능, D18). */
    @Enumerated(EnumType.STRING)
    @Column(name = "first_run_kind", length = 20)
    private ReservedRunKind firstRunKind;

    /**
     * 이 예약의 마지막 실행 시작 시각(KST). 현황 후보 조회(02 {@code findForList})에만 쓴다 —
     * 화면의 실행 시각은 결과 행의 {@link ReservedShipmentItem#getLastRunAt()} 이다(D28 · D30).
     */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;
}
