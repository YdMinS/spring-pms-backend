package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 택배사 마스터 (lookup).
 *
 * 실제 물류 택배사(예: "CJ대한통운") 1건을 나타내는 정규화 테이블. 플랫폼별 코드 체계는
 * {@link PlatformCarrierCode} 에 분리 저장한다(택배사 하나 × 플랫폼 다수).
 *
 * BaseEntity 를 상속한다(FEATURE_2609_59 / PLAN D1) — 사람이 화면에서 직접 만들고 고치는 기록이라
 * 등록일·수정일을 갖는다. 운영 DDL 은 changeset 095 가 맞춘다(ddl-auto=validate).
 * ⚠️ 기존 행의 두 컬럼은 NULL 이다 — 과거 등록 시점은 소급해서 알 수 없어 백필하지 않는다(D3).
 *    그래서 이 컬럼으로 목록을 정렬하면 옛 행이 한 덩어리로 뭉친다 — 정렬은 id 를 쓴다.
 */
@Entity
@Table(name = "carrier")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Carrier extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;                     // 예: "CJ대한통운"

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
