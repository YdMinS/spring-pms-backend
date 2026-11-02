package com.pms.repository;

import com.pms.domain.ShipmentParcelStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Parcel status history (FEATURE_2611_03 / PLAN D15 · D29 · D30 · D39).
 *
 * <p>❌ Do not call {@code save} from anywhere but {@link com.pms.service.packing.ParcelStatusRecorder}.
 */
public interface ShipmentParcelStatusLogRepository extends JpaRepository<ShipmentParcelStatusLog, Long> {

    /**
     * History rows in a period, newest first (D29 · D30).
     *
     * <p>🔴 {@code keyword} null = no keyword condition. Otherwise a row matches when the invoice number contains
     * {@code invoiceKeyword} (the keyword with hyphens and spaces stripped — stored invoice numbers are normalized)
     * OR the order number contains {@code keyword}, both case-insensitive (D39).
     *
     * <p>⚠️ JPQL only (no nativeQuery): the parcel / shipment tables are {@code @TenantId}, and only JPQL gets
     * the tenant filter. {@code join fetch} keeps one query for the parcel · order · seller columns (no N+1).
     *
     * @param from        lower bound, inclusive — UTC wall clock, the clock {@code created_date} is stored in
     * @param toExclusive upper bound, exclusive (the next KST midnight in UTC, D40)
     */
    @Query("""
            select l from ShipmentParcelStatusLog l
              join fetch l.shipmentParcel p
              join fetch p.orderShipment s
              join fetch s.order o
              join fetch o.marketplaceAccount a
              join fetch a.seller sel
             where l.createdAt >= :from and l.createdAt < :toExclusive
               and (:keyword is null
                    or lower(p.invoiceNumber) like lower(concat('%', :invoiceKeyword, '%'))
                    or lower(o.externalOrderId) like lower(concat('%', :keyword, '%')))
             order by l.createdAt desc, l.id desc
            """)
    List<ShipmentParcelStatusLog> search(@Param("from") LocalDateTime from,
                                         @Param("toExclusive") LocalDateTime toExclusive,
                                         @Param("keyword") String keyword,
                                         @Param("invoiceKeyword") String invoiceKeyword);
}
