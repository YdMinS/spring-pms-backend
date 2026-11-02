package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelStatusLog;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShipmentParcelStatusLogRepository#search} — period + keyword filter of the packing history
 * (FEATURE_2611_03 / PLAN D29 · D30 · D39). A mock cannot prove the JPQL filters, so it is checked here.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class ShipmentParcelStatusLogRepositoryTest {

    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 11, 2, 0, 0);
    private static final LocalDateTime NEXT_DAY = LocalDateTime.of(2026, 11, 3, 0, 0);

    @Autowired private ShipmentParcelStatusLogRepository statusLogRepository;
    @Autowired private TestEntityManager em;

    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        Seller seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
    }

    @Test
    void testSearchFiltersByPeriodAndKeywordNewestFirst() {
        ShipmentParcel first = parcel("ORD-AB-1", "255828253026");
        ShipmentParcel second = parcel("ORD-CD-2", "111122223333");
        Long packed = log(first, ParcelStatus.PENDING, ParcelStatus.PACKED, LocalDateTime.of(2026, 11, 2, 9, 0));
        Long unused = log(second, ParcelStatus.PENDING, ParcelStatus.UNUSED, LocalDateTime.of(2026, 11, 2, 10, 0));
        // Outside the period (the upper bound is exclusive).
        log(second, ParcelStatus.UNUSED, ParcelStatus.PENDING, NEXT_DAY);

        assertThat(ids(statusLogRepository.search(DAY_START, NEXT_DAY, null, null)))
                .containsExactly(unused, packed);
        // Invoice side: the service passes the keyword with hyphens stripped (D39).
        assertThat(ids(statusLogRepository.search(DAY_START, NEXT_DAY, "2558-2825", "25582825")))
                .containsExactly(packed);
        // Order side: contained and case-insensitive.
        assertThat(ids(statusLogRepository.search(DAY_START, NEXT_DAY, "ord-cd", "ord-cd")))
                .containsExactly(unused);
        assertThat(statusLogRepository.search(DAY_START, NEXT_DAY, "없는번호", "없는번호")).isEmpty();
    }

    private ShipmentParcel parcel(String externalOrderId, String invoiceNumber) {
        Order order = em.persist(Order.builder().marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(externalOrderId).orderedAt(LocalDateTime.of(2026, 11, 1, 10, 0)).build());
        OrderShipment shipment = em.persist(OrderShipment.builder().order(order)
                .externalShipmentId("BOX-" + externalOrderId).build());
        return em.persist(ShipmentParcel.builder().orderShipment(shipment).invoiceNumber(invoiceNumber)
                .carrierName("롯데택배").parcelSeq(1).status(ParcelStatus.PENDING).build());
    }

    /** Writes a row, then pins its created time with SQL — auditing stamps "now" on insert. */
    private Long log(ShipmentParcel parcel, ParcelStatus from, ParcelStatus to, LocalDateTime at) {
        ShipmentParcelStatusLog row = em.persist(ShipmentParcelStatusLog.builder()
                .shipmentParcel(parcel).fromStatus(from).toStatus(to).changedBy("packer@oclyx.com").build());
        em.flush();
        em.getEntityManager()
                .createNativeQuery("UPDATE shipment_parcel_status_log SET created_date = :at WHERE id = :id")
                .setParameter("at", at)
                .setParameter("id", row.getId())
                .executeUpdate();
        em.clear();
        return row.getId();
    }

    private List<Long> ids(List<ShipmentParcelStatusLog> rows) {
        return rows.stream().map(ShipmentParcelStatusLog::getId).toList();
    }
}
