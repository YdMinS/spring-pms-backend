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
 * The <b>one door</b> for creating physical-box rows from invoice numbers (FEATURE_2609_40 / PLAN D5 · D7).
 *
 * <p>There are two save paths and both go through here:
 * <ul>
 *   <li>Shipment confirm — {@link InvoiceWriteBack} (the carrier result file, bulk) · the marketplaces'
 *       {@link ShipmentInvoiceAdapter}s and {@link ElevenstManualShipper} (single manual) — FEATURE_2610_23 / D21</li>
 *   <li>Order sync backfill — {@link com.pms.service.coupang.OrderUpserter}</li>
 * </ul>
 *
 * <p>🔴 <b>An existing (delivery bundle, invoice number) is never touched</b>. The sync runs again and again, so
 * updating here would ① add a box every run (the unique constraint stops it) or ② overwrite the packing result of a
 * {@code PACKED} box, splitting the ledger from the screen.
 *
 * <p>🔴 {@code parcel_seq} is <b>the bundle's current row count + 1</b> — the same reason there is no total-count
 * column (a value you can count is not written in two places).
 *
 * <p>⚠️ Callers <b>must swallow failures</b> — a box save must not break the shipment confirm or the order sync.
 * This class does not hide exceptions (each call site knows which log it wants).
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
        // 저장값은 언제나 정규화된 송장번호다(하이픈·공백 제거) — 세 경로(단건·일괄·동기화 백필)가 여기로 모이므로
        // 여기서 한 번 벗기면 조회(스캔)·대사가 같은 값을 본다.
        String invoice = InvoiceNumbers.normalize(invoiceNumber);
        if (shipment == null || shipment.getId() == null || invoice == null || invoice.isEmpty()) {
            return Optional.empty();
        }
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
