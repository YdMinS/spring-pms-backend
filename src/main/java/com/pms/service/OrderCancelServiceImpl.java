package com.pms.service;

import com.pms.domain.OrderCancelReason;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.repository.OrderLineRepository;
import com.pms.service.OrderCancelResult.CancelledLine;
import com.pms.service.OrderCancelResult.FailedLine;
import com.pms.service.OrderCancelResult.SkippedLine;
import com.pms.service.claim.ActionChoice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link OrderCancelService} implementation — the seller cancel before shipping (FEATURE_2609_25); each marketplace
 * sends through its {@link OrderCancelAdapter} (FEATURE_2610_23 / D21 — {@link CoupangOrderCancelAdapter} ·
 * {@link ElevenstOrderCancelAdapter}).
 *
 * Flow: duplicate line-id check → {@code findWithAccountByIdIn} expand → reason check of each marketplace in the
 * request (FEATURE_2610_17 / D71) → one batch per adapter → each line goes to its marketplace's batch in request order
 * (no adapter = unsupported, D14) → the batches send in the platform order (Coupang → 11st).
 *
 * ⚠️ Never put {@code @Transactional} on this service — it is a path that runs external HTTP.
 *    {@code OrderLine} is read only through {@code @EntityGraph} finders (open-in-view=false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelServiceImpl implements OrderCancelService {

    private final OrderLineRepository orderLineRepository;
    /** FEATURE_2610_23 / D21: one cancel sender per marketplace. */
    private final List<OrderCancelAdapter> orderCancelAdapters;

    @Override
    public OrderCancelResult cancel(OrderCancelRequest request) {
        List<Long> ids = request.lines().stream().map(OrderCancelRequest.Line::orderItemId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            // 조용히 합치지 않는다 — 합치면 사용자가 의도하지 않은 수량이 되돌릴 수 없는 API 로 나간다.
            throw new IllegalArgumentException("같은 주문 라인이 중복되었습니다");
        }
        Map<Long, Integer> quantityById = new LinkedHashMap<>();
        request.lines().forEach(l -> quantityById.put(l.orderItemId(), l.quantity()));

        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(ids);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }
        checkReasons(lines, quantityById, request);
        // FEATURE_2610_23 / D21: one batch per adapter — EnumMap keeps the platform order (Coupang → 11st).
        Map<Platform, OrderCancelAdapter.Batch> batches = new EnumMap<>(Platform.class);
        for (Platform platform : Platform.values()) {
            adapterFor(platform).ifPresent(adapter -> batches.put(platform, adapter.begin(request, lines)));
        }

        List<CancelledLine> cancelled = new ArrayList<>();
        List<FailedLine> failed = new ArrayList<>();
        List<SkippedLine> skipped = new ArrayList<>();
        List<SkippedLine> unsupported = new ArrayList<>();

        for (OrderLine line : lines) {
            Integer quantity = quantityById.get(line.getId());
            if (quantity == null) {
                continue;                                   // 요청에 없는 라인은 무시(방어)
            }
            // 플랫폼 판정은 계정 기준 — 발주처리·발송처리와 같은 기준이어야 레그가 갈라지지 않는다.
            OrderCancelAdapter.Batch batch = batches.get(line.getOrder().getMarketplaceAccount().getPlatform());
            if (batch == null) {
                unsupported.add(OrderCancelAdapter.skippedLine(line, OrderCancelAdapter.UNSUPPORTED_MESSAGE));
                continue;
            }
            batch.add(line, quantity, skipped, unsupported);
        }

        batches.values().forEach(batch -> batch.send(cancelled, failed));

        int succeededQty = cancelled.stream().mapToInt(CancelledLine::cancelledQty).sum();
        log.info("주문취소 결과: lines={} succeeded={} qty={} failed={} skipped={} unsupported={}",
                lines.size(), cancelled.size(), succeededQty,
                failed.size(), skipped.size(), unsupported.size());

        return new OrderCancelResult(lines.size(), cancelled.size(), succeededQty,
                cancelled, failed, skipped, unsupported);
    }

    /**
     * FEATURE_2610_17 / D71: the reason of each marketplace present in the request is checked here — after the lines
     * are read (the platform is known) and before any grouping or sending, so one bad reason sends nothing. The
     * marketplaces are checked in the platform order (Coupang → 11st); each adapter knows its own reason fields.
     */
    private void checkReasons(List<OrderLine> lines, Map<Long, Integer> quantityById, OrderCancelRequest request) {
        Set<Platform> present = EnumSet.noneOf(Platform.class);
        for (OrderLine line : lines) {
            if (quantityById.containsKey(line.getId())) {
                present.add(line.getOrder().getMarketplaceAccount().getPlatform());
            }
        }
        for (Platform platform : present) {
            adapterFor(platform).ifPresent(adapter -> adapter.checkReason(request));
        }
    }

    @Override
    public List<ActionChoice> availableReasons() {
        return Arrays.stream(OrderCancelReason.values())
                .map(r -> new ActionChoice(r.name(), r.getLabel()))
                .toList();
    }

    /** The adapter whose platform matches — same lookup as {@code ClaimActionServiceImpl.resolve()}. */
    private Optional<OrderCancelAdapter> adapterFor(Platform platform) {
        return orderCancelAdapters.stream().filter(adapter -> adapter.platform() == platform).findFirst();
    }
}
