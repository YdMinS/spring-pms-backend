package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.dto.response.LotteonInvoiceRecordsResponse;
import com.pms.dto.response.LotteonInvoiceRecordsResponse.Record;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.CarrierOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link LotteonOrderSellerService} implementation (FEATURE_2611_23 / D19 ⑥-5 · R17 ~ R19).
 *
 * <p>⚠️ No {@code @Transactional}: LotteON HTTP runs here (same as {@code ElevenstOrderSellerServiceImpl}).
 * Carrier names come from {@link CarrierCodeService#findOptions} only — the catalog repository is not read directly
 * (R19).
 */
@Service
@RequiredArgsConstructor
public class LotteonOrderSellerServiceImpl implements LotteonOrderSellerService {

    static final String NOT_LOTTEON_MESSAGE = "롯데ON 주문이 아닙니다";

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

    private static Record record(JsonNode row, Map<String, String> carrierNames) {
        String carrierCode = LotteonOrderRows.text(row, "dvCoCd");
        String carrierName = carrierCode == null ? null : carrierNames.getOrDefault(carrierCode, carrierCode);
        String step = LotteonOrderRows.text(row, "odPrgsStepCd");
        return new Record(LotteonOrderRows.text(row, "procSeq"), LotteonOrderRows.text(row, "invcNo"), carrierCode,
                carrierName, step, LotteonOrderRows.stepName(step),
                LotteonOrderApi.parse(LotteonOrderRows.text(row, "dvTrcStatDttm")));
    }
}
