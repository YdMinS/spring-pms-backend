package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 플랫폼별 택배사 코드 (lookup).
 *
 * 하나의 {@link Carrier} 가 플랫폼마다 다른 코드를 갖는다(예: 쿠팡 = "CJGLS"). 발송처리(송장업로드)
 * 레그가 `계정.platform → deliveryCompanyCode` 를 얻는 매핑의 원천.
 * (carrier_id, platform) 은 UNIQUE — 한 택배사×플랫폼 조합은 코드 1개.
 *
 * ⚠️ BaseEntity 를 상속하지 않는다({@link Carrier} 와 동일 — lookup 마스터, 감사컬럼 불필요).
 */
@Entity
@Table(name = "platform_carrier_code",
        uniqueConstraints = @UniqueConstraint(name = "uq_platform_carrier_code",
                columnNames = {"carrier_id", "platform"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformCarrierCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "carrier_id", nullable = false)
    private Carrier carrier;

    @Column(nullable = false, length = 50)
    private String platform;                 // "COUPANG", "NAVER"...

    @Column(name = "delivery_company_code", nullable = false, length = 50)
    private String deliveryCompanyCode;      // 예: 쿠팡 "CJGLS"
}
