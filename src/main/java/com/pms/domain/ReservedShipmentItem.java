package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 예약 결과 1행 = 예약 안의 배송 묶음 1개 (FEATURE_2609_75 / D22 · D27 · D28).
 *
 * <p>🔴 예약 대기 중 송장은 여기에만 있다 — {@code shipment_parcel} 은 쿠팡 송장 등록이 <b>성공한 뒤</b>에 만든다(D22).
 * <p>{@code invoiceNumbers} = 결과 파일 송장을 쉼표로 이은 값. 첫 번째가 쿠팡에 올리는 대표 송장이다(2609_40 D8).
 * <p>🔴 {@code reservedShipment} null = 예약 없이 송장만 보관한 행({@link ReservedItemResult#STORED}, D18). 배송 묶음 1개의
 * 현재 송장 = 결과가 STORED·PENDING·FAILED 인 행 1개다(02 {@code ReservedShipmentServiceImpl.liveItems}).
 */
@Entity
@Table(name = "reserved_shipment_item")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ReservedShipmentItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** null = 예약 없이 송장만 보관한 행(result STORED, D18). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reserved_shipment_id")
    private ReservedShipment reservedShipment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_shipment_id", nullable = false)
    private OrderShipment orderShipment;

    @Column(name = "external_order_id", nullable = false, length = 100)
    private String externalOrderId;

    @Column(name = "carrier_code", nullable = false, length = 50)
    private String carrierCode;

    @Column(name = "invoice_numbers", nullable = false, length = 1000)
    private String invoiceNumbers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservedItemProgress progress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservedItemResult result;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * 이 행(주문)의 마지막 실행 시각(KST, D28 · D30). 그 행이 실행에서 시도·종료될 때 실행기(02)가 쓴다 — 같은 예약의
     * 다른 행이 나중에 재시도돼도 이미 끝난 행의 값은 바뀌지 않는다. null = 이 행은 아직 실행 전.
     */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 쉼표로 이은 송장 → 목록. 첫 번째가 대표 송장. */
    public List<String> invoiceNumberList() {
        return Arrays.stream(invoiceNumbers.split(",")).filter(s -> !s.isBlank()).toList();
    }

    /**
     * 예약 없이 송장만 보관하는 사본(D18 — [예약 취소] 뒤에도 송장은 주문에 남는다). 새 행으로 저장한다 —
     * id·예약·사유·실행 시각을 비우고 단계는 NONE, 결과는 STORED.
     */
    public ReservedShipmentItem toStored() {
        return toBuilder().id(null).reservedShipment(null).progress(ReservedItemProgress.NONE)
                .result(ReservedItemResult.STORED).failureReason(null).lastRunAt(null).build();
    }
}
