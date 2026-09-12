package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 플랫폼이 받아주는 택배사 코드 전량 (lookup, PLAN 2609_37 D1).
 *
 * <p>플랫폼 × 코드 × 이름 = 그 마켓의 공식 택배사 코드표 사본이다. 단건 발송처리 드롭다운과
 * 송장업로드 전 화이트리스트가 <b>이 표 하나</b>를 읽는다 — 자바 하드코딩 표는 이 테이블로 대체되어
 * 삭제됐다(changeset 087 이 시드한다).
 *
 * <p>⚠️ 로컬 {@link Carrier}/{@link PlatformCarrierCode}(택배사 관리)와 <b>다른 것</b>이다(D6).
 * 택배사 관리는 택배비(CarrierRate)를 매기는 우리 업무 데이터이고, 이 표는 플랫폼이 받아주는 코드 전량이다.
 * 둘을 FK 로 묶지 않는다 — 카탈로그를 재시드할 때 계약 행이 깨진다.
 *
 * <p>🔴 <b>테넌트 공용</b>(D2): {@code tenant_id} 가 없다. "쿠팡에서 CJ대한통운 = CJGLS" 는 테넌트마다
 * 다를 수 없다. 감사컬럼도 없다({@code BaseEntity} 미상속 — {@link Carrier} 와 같은 lookup 마스터).
 *
 * <p>🔴 <b>갱신은 changeset 뿐</b>(D3): 관리 CRUD·화면·POST/PUT/DELETE 엔드포인트를 만들지 않는다.
 * 폐기된 코드는 다음 changeset 에서 <b>행을 지운다</b>({@code is_active} 컬럼을 두지 않는다 — D5).
 *
 * @see com.pms.service.CarrierCodeService 이 표를 읽는 유일한 서비스
 */
@Entity
@Table(name = "carrier_catalog")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CarrierCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Platform platform;

    @Column(nullable = false, length = 50)
    private String code;                     // 그 플랫폼의 deliveryCompanyCode (예: "CJGLS")

    @Column(nullable = false, length = 100)
    private String name;                     // 표시 이름 (예: "CJ대한통운")

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;            // 플랫폼별 정렬값 (D4)
}
