package com.pms.service;

import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.repository.ShipmentParcelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 송장번호 → 실물 박스 행 생성의 <b>유일한 창구</b> (FEATURE_2609_40 / PLAN D5 · D7).
 *
 * <p>저장 경로는 둘이고 둘 다 여기를 통한다:
 * <ul>
 *   <li>발송처리 — {@link ShipmentConfirmServiceImpl}(택배사 결과 파일 일괄 · 단건 수동)</li>
 *   <li>주문 동기화 백필 — {@link com.pms.service.coupang.OrderUpserter}</li>
 * </ul>
 *
 * <p>🔴 <b>이미 있는 (배송묶음, 송장번호) 는 손대지 않는다</b>. 동기화는 반복 실행되므로 여기서 갱신하면
 * ① 실행마다 박스가 늘거나(유일 제약이 막는다) ② {@code PACKED} 인 박스의 포장 결과를 덮어
 * 원장과 화면이 갈라진다.
 *
 * <p>🔴 {@code parcel_seq} 는 <b>그 배송 묶음의 현재 행 수 + 1</b> 이다. 총 개수 컬럼을 두지 않는 이유와 같다
 * (세면 나오는 값을 두 곳에 적지 않는다).
 *
 * <p>⚠️ 호출자는 <b>실패를 삼켜야 한다</b> — 박스 저장이 발송처리·주문 동기화를 깨뜨리면 안 된다.
 * 이 클래스는 예외를 감추지 않는다(호출 지점마다 어떤 로그를 남길지는 호출자가 안다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentParcelRecorder {

    private final ShipmentParcelRepository shipmentParcelRepository;

    /**
     * 배송 묶음에 송장 1장짜리 실물 박스를 기록한다. 이미 같은 송장이 있으면 <b>그 행을 그대로</b> 돌려준다.
     *
     * @param shipment      부모 배송 묶음. null 이면 아무것도 하지 않는다(빈 shipmentBoxId 주문)
     * @param invoiceNumber 송장번호. 비어 있으면 아무것도 하지 않는다
     * @param carrierCode   플랫폼 택배사 코드. 모르면 null (D6 — 코드가 없다고 송장을 버리지 않는다)
     * @param carrierName   마켓이 준 택배사 이름 원문. 없으면 null
     * @return 새로 만들었거나 이미 있던 박스. 저장할 수 없는 입력이면 empty
     */
    @Transactional
    public Optional<ShipmentParcel> record(OrderShipment shipment, String invoiceNumber,
                                           String carrierCode, String carrierName) {
        if (shipment == null || shipment.getId() == null || invoiceNumber == null || invoiceNumber.isBlank()) {
            return Optional.empty();
        }
        String invoice = invoiceNumber.trim();
        Optional<ShipmentParcel> existing =
                shipmentParcelRepository.findByOrderShipment_IdAndInvoiceNumber(shipment.getId(), invoice);
        if (existing.isPresent()) {
            return existing;                     // 멱등 — 상태(PACKED 포함)를 건드리지 않는다
        }

        int seq = (int) shipmentParcelRepository.countByOrderShipment_Id(shipment.getId()) + 1;
        ShipmentParcel saved = shipmentParcelRepository.save(ShipmentParcel.builder()
                .orderShipment(shipment)
                .invoiceNumber(invoice)
                .carrierCode(blankToNull(carrierCode))
                .carrierName(blankToNull(carrierName))
                .parcelSeq(seq)
                .status(ParcelStatus.PENDING)
                .build());
        log.info("[parcel] 실물 박스 생성: shipment={} invoice={} seq={}", shipment.getId(), invoice, seq);
        return Optional.of(saved);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
