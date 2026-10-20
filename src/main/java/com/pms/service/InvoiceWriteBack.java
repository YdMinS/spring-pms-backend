package com.pms.service;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.repository.OrderLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The local records around an invoice send that every marketplace shares (FEATURE_2610_23 / D21) — the
 * {@code SHIPPED} write-back, the physical boxes ({@code shipment_parcel}) and the bundle-id helpers. Moved from
 * {@link ShipmentConfirmServiceImpl} unchanged (D3); used by it and by the {@link ShipmentInvoiceAdapter}s.
 *
 * <p>⚠️ Nothing here calls a marketplace, and nothing here throws (see each method).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceWriteBack {

    /** 신규 업로드 성공 시 로컬 라인이 갖는 상태(PLAN 2609_07 D4). */
    static final OrderStatus STATUS_SHIPPED = OrderStatus.SHIPPED;

    private final OrderLineRepository orderLineRepository;
    private final ShipmentParcelRecorder shipmentParcelRecorder;
    private final CarrierCodeService carrierCodeService;

    /**
     * Sets the local status of the boxes whose invoice upload succeeded to {@code SHIPPED} (PLAN 2609_07 D4).
     * The 11st path calls it with the delivery numbers 11st accepted (FEATURE_2610_17 / D2).
     *
     * <p>A re-upload is filtered out at once, and the order list shows the new status without waiting for a sync.
     * The next order sync writes the same value (Coupang DEPARTURE · 11st 401 list → SHIPPED), so nothing conflicts.</p>
     *
     * ⚠️ Never throw here — the marketplace already accepted the invoice; an exception caught by the per-account
     *    catch would report the succeeded box as failed and make the user upload again (duplicate send, PLAN 2609_07 D5).
     *    → the <b>whole body</b> is wrapped in try, not only saveAll (an exception while building entities must not leak).
     */
    public void markDeparted(List<String> succeededBoxIds, Map<String, List<OrderLine>> dbLinesByBoxId) {
        try {
            List<OrderLine> updated = new ArrayList<>();
            for (String boxId : succeededBoxIds) {
                for (OrderLine line : dbLinesByBoxId.getOrDefault(boxId, List.of())) {
                    updated.add(line.toBuilder().status(STATUS_SHIPPED).build());   // 동기화 upsert 와 같은 패턴
                }
            }
            if (updated.isEmpty()) {
                return;                               // 폴백으로만 전송된 주문 = DB 행 없음
            }
            orderLineRepository.saveAll(updated);
        } catch (Exception e) {
            log.warn("발송처리 로컬 상태 갱신 실패(쿠팡 전송은 성공): boxes={}", succeededBoxIds, e);
        }
    }

    /**
     * 결과 파일의 송장을 실물 박스({@code shipment_parcel})로 남긴다 (PLAN 2609_40 D8 · D30).
     *
     * <p>🔴 <b>그 주문의 배송 묶음이 하나일 때만</b> 만든다. 결과 파일이 주는 것은 (주문번호, 송장번호) 뿐이고
     * 묶음 식별자가 없어 <b>어느 묶음의 송장인지 알 수 없다</b> — 추측해서 붙이면 스캔한 송장이 남의 박스를 연다.
     * 묶음이 2개 이상이면 경고만 남기고, 주문 동기화 백필이 묶음별로 정확히 채운다(D30).
     *
     * <p>🔴 묶음 수를 세는 소스는 <b>그 주문의 전체 라인</b>이다 — {@code sendable} 만 세면 박스 2개 중 하나가
     * 이미 발송된 주문이 「묶음 1개」로 세어져 가드를 빠져나간다.
     *
     * <p>⚠️ 전송 성공 여부와 무관하게 저장한다(송장은 이미 발급됐고 작업자는 그걸 들고 싼다).
     * ⚠️ 여기서 던지면 안 된다 — 박스 저장 실패가 발송처리를 막으면 안 된다.
     */
    public void recordParcels(String orderId, List<OrderLine> lines, List<String> invoiceNumbers,
                              Map<Platform, String> carrierCodeCache) {
        if (invoiceNumbers == null || invoiceNumbers.isEmpty() || lines.isEmpty()) {
            return;
        }
        try {
            Map<String, OrderShipment> shipmentsById = new LinkedHashMap<>();
            for (OrderLine line : lines) {
                String boxId = boxIdOf(line);
                if (boxId != null && !boxId.isBlank() && line.getOrderShipment() != null) {
                    shipmentsById.putIfAbsent(boxId, line.getOrderShipment());
                }
            }
            if (shipmentsById.size() != 1) {
                log.warn("발송처리 실물 박스 생성 생략 — 배송 묶음 {}개 · 송장 {}장: orderId={} (PLAN 2609_40 D30, "
                                + "주문 동기화가 묶음별로 채운다)",
                        shipmentsById.size(), invoiceNumbers.size(), orderId);
                return;
            }
            OrderShipment shipment = shipmentsById.values().iterator().next();
            Platform platform = lines.get(0).getOrder().getMarketplaceAccount().getPlatform();
            String carrierCode = carrierCodeQuietly(platform, carrierCodeCache);
            for (String invoiceNumber : invoiceNumbers) {
                shipmentParcelRecorder.record(shipment, invoiceNumber, carrierCode, null);
            }
        } catch (Exception e) {
            log.warn("발송처리 실물 박스 저장 실패(전송은 계속): orderId={}", orderId, e);
        }
    }

    /**
     * 박스에 적을 택배사 코드 — 못 얻어도 진행한다(PLAN 2609_40 D6).
     *
     * <p>전송용 {@code resolveDeliveryCompanyCode} 는 미설정 시 던지지만, 여기서는 코드가 없다고
     * 송장번호까지 버릴 이유가 없다. 플랫폼당 1회만 읽고 캐시한다(실패도 캐시 — 재조회 무의미).
     */
    private String carrierCodeQuietly(Platform platform, Map<Platform, String> cache) {
        if (platform == null) {
            return null;
        }
        // ⚠️ computeIfAbsent 를 쓰지 않는다 — null(미해석)을 저장하지 않아 주문마다 다시 조회하게 된다.
        if (cache.containsKey(platform)) {
            return cache.get(platform);
        }
        String code = null;
        try {
            code = carrierCodeService.resolveDeliveryCompanyCode(platform);
        } catch (Exception e) {
            log.info("실물 박스 택배사 코드 미해석(송장번호만 저장): platform={} reason={}", platform, e.getMessage());
        }
        cache.put(platform, code);
        return code;
    }

    /**
     * 성공 시 write-back 할 DB 라인을 박스 id 로 색인한다.
     *
     * <p>배송 묶음은 nullable 이므로 널/공백 키는 담지 않는다. DB 값과 응답 {@code shipmentBoxId} 는
     * 같은 쿠팡 id 의 문자열이므로(동기화가 {@code box.path("shipmentBoxId").asText()} 로 넣은 값)
     * 숫자 변환·패딩 없이 문자열 그대로 비교한다.</p>
     */
    public void registerWriteBack(List<OrderLine> lines, Map<String, List<OrderLine>> target) {
        for (OrderLine line : lines) {
            String boxId = boxIdOf(line);
            if (boxId == null || boxId.isBlank()) {
                continue;
            }
            target.computeIfAbsent(boxId, k -> new ArrayList<>()).add(line);
        }
    }

    /** The representative invoice sent to the marketplace = the first one in file order (D8 · FEATURE_2610_17 / D2). */
    public static String representativeInvoice(Map<String, List<String>> invoicesByOrderId, String orderId) {
        List<String> invoices = invoicesByOrderId.get(orderId);
        return (invoices == null || invoices.isEmpty()) ? null : invoices.get(0);
    }

    /** Delivery bundle id (Coupang shipmentBoxId · 11st dlvNo). Null when there is no bundle. */
    public static String boxIdOf(OrderLine line) {
        return (line.getOrderShipment() == null) ? null : line.getOrderShipment().getExternalShipmentId();
    }

    /** 스킵 보고용 상태 이름 — 중립 상태다(원문 라벨 변환은 클라이언트 몫, D9). */
    public static String statusName(OrderLine line) {
        return (line.getStatus() == null) ? null : line.getStatus().name();
    }
}
