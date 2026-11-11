package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.LotteonDeliveryAppointmentRequest;
import com.pms.dto.response.LotteonInvoiceRecordsResponse;
import com.pms.dto.response.LotteonInvoiceRecordsResponse.Record;
import com.pms.dto.response.LotteonOrderChoicesResponse;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.CarrierOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link LotteonOrderSellerService} implementation (FEATURE_2611_23 / D19 ⑥-5 · R17 ~ R19).
 *
 * <p>⚠️ No {@code @Transactional}: LotteON HTTP runs here (same as {@code ElevenstOrderSellerServiceImpl}).
 * Carrier names come from {@link CarrierCodeService#findOptions} only — the catalog repository is not read directly
 * (R19).
 *
 * <p>FEATURE_2611_23 / 06: the LotteON code lists (D58) and [발송 약정일 안내] ([138], D26 · R25).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteonOrderSellerServiceImpl implements LotteonOrderSellerService {

    static final String NOT_LOTTEON_MESSAGE = "롯데ON 주문이 아닙니다";
    static final String APPOINTMENT_STATUS_MESSAGE = "결제완료 · 상품준비중 주문만 발송 약정일을 안내할 수 있습니다";
    static final String APPOINTMENT_REASON_MESSAGE = "지연 사유를 선택하세요";
    static final String APPOINTMENT_DETAIL_MESSAGE = "제작지연은 상세 사유를 선택하세요";

    /** R25: [138] is accepted at 11 출고지시 · 12 상품준비 only. */
    private static final Set<OrderStatus> APPOINTMENT_STATUSES = Set.of(OrderStatus.PAID, OrderStatus.PREPARING);
    private static final DateTimeFormatter APPOINTMENT_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** D26: the promised day ends at 23:59:59. */
    private static final String END_OF_DAY = "235959";

    private final OrderLineRepository orderLineRepository;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;
    private final LotteonOrderApi lotteonOrderApi;
    private final CarrierCodeService carrierCodeService;

    @Override
    public List<LotteonInvoiceRecordsResponse> invoiceRecords(Long orderItemId) {
        OrderLine line = orderLineRepository.findWithAccountAndSellerById(orderItemId)
                .orElseThrow(() -> new IllegalArgumentException("주문 라인을 찾을 수 없습니다"));
        MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
        if (!Platform.LOTTEON.equals(account.getPlatform()) || line.getOrderShipment() == null) {
            throw new IllegalArgumentException(NOT_LOTTEON_MESSAGE);
        }
        if (lotteonOrderLineRepository.findByOrderLine_IdIn(List.of(orderItemId)).isEmpty()) {
            throw new IllegalArgumentException(NOT_LOTTEON_MESSAGE);
        }
        String bundleId = line.getOrderShipment().getExternalShipmentId();
        String externalOrderId = line.getOrder().getExternalOrderId();
        List<OrderLine> lines = orderLineRepository.findByExternalOrderId(externalOrderId).stream()
                .filter(l -> l.getOrder().getMarketplaceAccount().getId().equals(account.getId()))
                .filter(l -> l.getOrderShipment() != null
                        && bundleId.equals(l.getOrderShipment().getExternalShipmentId()))
                .sorted(Comparator.comparing(OrderLine::getId))
                .toList();
        Map<Long, LotteonOrderLine> mirrors = lotteonOrderLineRepository
                .findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        List<JsonNode> states = lotteonOrderApi.progressStatesOfOrder(account, externalOrderId);
        Map<String, String> carrierNames = new LinkedHashMap<>();
        for (CarrierOption option : carrierCodeService.findOptions(Platform.LOTTEON)) {
            carrierNames.putIfAbsent(option.deliveryCompanyCode(), option.carrierName());
        }
        return lines.stream()
                .map(l -> {
                    LotteonOrderLine mirror = mirrors.get(l.getId());
                    List<Record> records = mirror == null ? List.of()
                            : LotteonOrderRows.shipmentRecords(states, mirror.getOdSeq()).stream()
                                    .map(row -> record(row, carrierNames))
                                    .toList();
                    return new LotteonInvoiceRecordsResponse(l.getId(), l.getItemName(), records);
                })
                .toList();
    }

    @Override
    public LotteonOrderChoicesResponse choices() {
        return new LotteonOrderChoicesResponse(LotteonSellerChoices.SELLER_CANCEL_REASONS,
                LotteonSellerChoices.CANCEL_REJECT_REASONS, LotteonSellerChoices.APPOINTMENT_REASONS,
                LotteonSellerChoices.APPOINTMENT_DETAIL_REASONS, LotteonSellerChoices.HOLD_REASONS);
    }

    @Override
    public void deliveryAppointment(Long orderItemId, LotteonDeliveryAppointmentRequest request) {
        OrderLine line = orderLineRepository.findWithAccountAndSellerById(orderItemId)
                .orElseThrow(() -> new IllegalArgumentException("주문 라인을 찾을 수 없습니다"));
        MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
        if (!Platform.LOTTEON.equals(account.getPlatform()) || line.getOrderShipment() == null) {
            throw new IllegalArgumentException(NOT_LOTTEON_MESSAGE);
        }
        if (lotteonOrderLineRepository.findByOrderLine_IdIn(List.of(orderItemId)).isEmpty()) {
            throw new IllegalArgumentException(NOT_LOTTEON_MESSAGE);
        }
        if (!APPOINTMENT_STATUSES.contains(line.getStatus())) {
            throw new IllegalArgumentException(APPOINTMENT_STATUS_MESSAGE);
        }
        String reasonCode = request.reasonCode() == null ? null : request.reasonCode().trim();
        LotteonSellerChoices.requireLabel(LotteonSellerChoices.APPOINTMENT_REASONS, reasonCode,
                APPOINTMENT_REASON_MESSAGE);
        String detail = "";
        if (LotteonSellerChoices.MAKING_DELAY.equals(reasonCode)) {
            detail = request.detailCode() == null ? null : request.detailCode().trim();
            LotteonSellerChoices.requireLabel(LotteonSellerChoices.APPOINTMENT_DETAIL_REASONS, detail,
                    APPOINTMENT_DETAIL_MESSAGE);
        }
        // R25 · D26: every open line of the same order and sales channel (no delivery-bundle filter).
        List<OrderLine> lines = orderLineRepository.findByExternalOrderId(line.getOrder().getExternalOrderId())
                .stream()
                .filter(l -> l.getOrder().getMarketplaceAccount().getId().equals(account.getId()))
                .filter(l -> APPOINTMENT_STATUSES.contains(l.getStatus()))
                .sorted(Comparator.comparing(OrderLine::getId))
                .toList();
        Map<Long, LotteonOrderLine> mirrors = lotteonOrderLineRepository
                .findByOrderLine_IdIn(lines.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), Function.identity(), (a, b) -> a));
        String promisedAt = request.appointmentDate().format(APPOINTMENT_DAY) + END_OF_DAY;
        List<Map<String, Object>> body = new ArrayList<>();
        for (OrderLine l : lines) {
            LotteonOrderLine mirror = mirrors.get(l.getId());
            if (mirror == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("odNo", mirror.getOdNo());
            item.put("odSeq", mirror.getOdSeq());
            if (mirror.getProcSeq() != null) {
                item.put("procSeq", mirror.getProcSeq());
            }
            item.put("sndDlRsnCd", reasonCode);
            item.put("sndDlRsnCnts", detail);
            item.put("sndAgrdDttm", promisedAt);
            body.add(item);
        }
        lotteonOrderApi.informAppointment(account, body);
        log.info("롯데ON 발송 약정일 안내: line={} odNo={} lines={} until={}", orderItemId,
                line.getOrder().getExternalOrderId(), body.size(), promisedAt);
    }

    private static Record record(JsonNode row, Map<String, String> carrierNames) {
        String carrierCode = LotteonOrderRows.text(row, "dvCoCd");
        String carrierName = carrierCode == null ? null : carrierNames.getOrDefault(carrierCode, carrierCode);
        String step = LotteonOrderRows.text(row, "odPrgsStepCd");
        return new Record(LotteonOrderRows.text(row, "procSeq"), LotteonOrderRows.text(row, "invcNo"), carrierCode,
                carrierName, step, LotteonOrderRows.stepName(step),
                LotteonOrderApi.parse(LotteonOrderRows.text(row, "dvTrcStatDttm")));
    }
}
