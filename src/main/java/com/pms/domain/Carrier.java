package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 택배사 마스터 (lookup).
 *
 * 실제 물류 택배사(예: "CJ대한통운") 1건을 나타내는 정규화 테이블. 플랫폼별 코드 체계는
 * {@link PlatformCarrierCode} 에 분리 저장한다(택배사 하나 × 플랫폼 다수).
 *
 * ⚠️ BaseEntity 를 상속하지 않는다 — lookup 마스터라 감사 이력(created/modified)이 불필요하며,
 *    상속 시 감사컬럼이 생겨 운영(MySQL) DDL(ddl-auto=validate)과 어긋난다.
 */
@Entity
@Table(name = "carrier")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Carrier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;                     // 예: "CJ대한통운"

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
