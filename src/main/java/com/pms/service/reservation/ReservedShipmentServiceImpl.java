package com.pms.service.reservation;

import com.pms.domain.InternalShipmentStage;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ReservedItemProgress;
import com.pms.domain.ReservedItemResult;
import com.pms.domain.ReservedRunKind;
import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentItem;
import com.pms.domain.ReservedShipmentStatus;
import com.pms.dto.response.ReservationCreateResult;
import com.pms.dto.response.ReservationCreateResult.ExcludedOrder;
import com.pms.dto.response.ReservedShipmentRowResponse;
import com.pms.dto.response.StoredInvoiceResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ReservedShipmentItemRepository;
import com.pms.repository.ReservedShipmentRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.InvoiceNumbers;
import com.pms.service.ReservedInvoice;
import com.pms.service.ShipmentConfirmResult;
import com.pms.service.ShipmentConfirmService;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ReservedShipmentService} 구현 (FEATURE_2609_75).
 *
 * <p>🔴 [예약 발송]은 {@code shipment_parcel} 을 만들지 않는다 — 송장은 예약 결과 행에만 둔다(D22·D27).
 * <p>🔴 시각은 전부 KST({@code SyncWindow.KST})로 판정한다(D4).
 * <p>🔴 내부 단계는 결제완료(PAID) 라인이 있는 배송 묶음에서만 인정한다(D29) — {@link #paidInternal} 하나로 판정한다.
 * <p>🔴 사용자에게 보이는 단위는 주문(결과 행)이다(D30) — 행 작업은 {@link #detach} 로 그 행만 움직인다.
 * <p>🔴 송장은 배송 묶음 단위다(D18 🔁) — 현재 송장 = {@link #liveItems}(STORED·PENDING·FAILED 중 id 최대). 예약 없는 보관 행은 STORED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservedShipmentServiceImpl implements ReservedShipmentService {

    public static final String PAST_TIME_MESSAGE = "지난 시각은 고를 수 없습니다";
    public static final String STARTED_MESSAGE = "실행이 시작된 예약은 바꿀 수 없습니다";
    public static final String NOT_INTERNAL_MESSAGE = "내부 상품준비중 주문이 아닙니다";
    public static final String NO_STORED_INVOICE_MESSAGE = "저장된 송장이 없습니다";
    public static final String ALREADY_RESERVED_MESSAGE = "이미 예약된 주문입니다";
    /** 결과 행 1개에 담는 송장 상한 — `invoice_numbers` VARCHAR(1000) ÷ (송장 60자 + 쉼표). 넘는 송장은 버리고 WARN. */
    static final int MAX_INVOICES_PER_ITEM = 16;
    private static final long LIST_DAYS = 7;
    private static final Set<ReservedShipmentStatus> OPEN_STATUSES = EnumSet.of(
            ReservedShipmentStatus.SCHEDULED, ReservedShipmentStatus.RUNNING, ReservedShipmentStatus.STOPPED);
    private static final Set<ReservedItemResult> OPEN_RESULTS =
            EnumSet.of(ReservedItemResult.PENDING, ReservedItemResult.FAILED);
    /** 배송 묶음의 현재 송장이 될 수 있는 결과(D18). */
    private static final List<ReservedItemResult> LIVE_RESULTS =
            List.of(ReservedItemResult.STORED, ReservedItemResult.PENDING, ReservedItemResult.FAILED);

    private final ShipmentConfirmService shipmentConfirmService;
    private final CarrierCodeService carrierCodeService;
    private final OrderLineRepository orderLineRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final ReservedShipmentRepository reservedShipmentRepository;
    private final ReservedShipmentItemRepository reservedShipmentItemRepository;
    private final ReservedShipmentSettler reservedShipmentSettler;
    private final InternalShipmentStageService internalShipmentStageService;
    private final ReservedShipmentExecutor reservedShipmentExecutor;

    @Override
    @Transactional
    public ReservationCreateResult create(MultipartFile file, LocalDateTime executeAt) {
        requireFuture(executeAt);
        Map<String, List<String>> invoicesByOrderId = shipmentConfirmService.readInvoicesByOrderId(file);
        if (invoicesByOrderId.isEmpty()) {
            throw new IllegalArgumentException("파일에 주문번호·운송장번호가 있는 행이 없습니다");
        }
        String carrierCode = resolveCarrierCode();

        ReservedShipment reservation = null;
        int reserved = 0;
        int updated = 0;
        List<ExcludedOrder> excluded = new ArrayList<>();
        List<Long> toAwaiting = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : invoicesByOrderId.entrySet()) {
            String orderId = entry.getKey();
            String invoices = joinInvoices(orderId, entry.getValue());
            List<OrderLine> lines = orderLineRepository.findByExternalOrderId(orderId);
            if (lines.isEmpty()) {
                excluded.add(new ExcludedOrder(orderId, "주문을 찾을 수 없습니다"));
                continue;
            }
            if (!Platform.COUPANG.equals(lines.get(0).getOrder().getMarketplaceAccount().getPlatform())) {
                excluded.add(new ExcludedOrder(orderId, "쿠팡 주문이 아닙니다"));
                continue;
            }
            Map<Long, OrderShipment> paid = paidInternal(lines);
            List<OrderShipment> internal = paid.values().stream()
                    .filter(s -> s.getInternalStage() == InternalShipmentStage.INTERNAL_PREPARING).toList();
            List<Long> awaitingIds = paid.values().stream()
                    .filter(s -> s.getInternalStage() == InternalShipmentStage.AWAITING_SHIPMENT)
                    .map(OrderShipment::getId).toList();
            if (internal.isEmpty() && awaitingIds.isEmpty()) {
                excluded.add(new ExcludedOrder(orderId, NOT_INTERNAL_MESSAGE));
                continue;
            }
            if (!awaitingIds.isEmpty()) {
                // D18 🔁 송장 수정(재업로드) — 대기·실패 결과의 송장을 바꾼다. 막는 것은 실행 중(RUNNING)뿐이다.
                List<ReservedShipmentItem> open = reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(
                        awaitingIds, List.of(ReservedItemResult.PENDING, ReservedItemResult.FAILED));
                if (open.stream().anyMatch(i -> i.getReservedShipment().getStatus() == ReservedShipmentStatus.RUNNING)) {
                    excluded.add(new ExcludedOrder(orderId, InternalShipmentStageServiceImpl.RUNNING_MESSAGE));
                } else {
                    for (ReservedShipmentItem item : open) {
                        reservedShipmentItemRepository.save(item.toBuilder()
                                .invoiceNumbers(invoices).carrierCode(carrierCode).build());
                    }
                    updated += open.size();
                }
            }
            if (!internal.isEmpty()) {
                if (reservation == null) {
                    reservation = newReservation(executeAt);
                }
                Map<Long, ReservedShipmentItem> stored = liveItems(internal.stream().map(OrderShipment::getId).toList());
                for (OrderShipment shipment : internal) {
                    // D18 — 보관 송장(STORED) 행이 있으면 그 행을 이 예약으로 옮기고 파일 송장으로 바꾼다(행을 늘리지 않는다).
                    ReservedShipmentItem base = stored.get(shipment.getId());
                    ReservedShipmentItem.ReservedShipmentItemBuilder next = base != null
                            ? base.toBuilder()
                            : ReservedShipmentItem.builder().orderShipment(shipment).externalOrderId(orderId);
                    reservedShipmentItemRepository.save(next
                            .reservedShipment(reservation)
                            .carrierCode(carrierCode)
                            .invoiceNumbers(invoices)
                            .progress(ReservedItemProgress.NONE)
                            .result(ReservedItemResult.PENDING)
                            .failureReason(null)
                            .build());
                    toAwaiting.add(shipment.getId());
                    reserved++;
                }
            }
        }
        if (!toAwaiting.isEmpty()) {
            orderShipmentRepository.setInternalStage(toAwaiting, InternalShipmentStage.AWAITING_SHIPMENT.name());
        }
        log.info("예약 발송 생성: reservation={} executeAt={} reserved={} updated={} excluded={}",
                reservation == null ? null : reservation.getId(), executeAt, reserved, updated, excluded.size());
        return new ReservationCreateResult(reservation == null ? null : reservation.getId(), executeAt,
                reserved, updated, excluded);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservedShipmentRowResponse> list() {
        LocalDateTime since = LocalDate.now(SyncWindow.KST).minusDays(LIST_DAYS).atStartOfDay();
        List<ReservedShipment> reservations = reservedShipmentRepository.findForList(OPEN_STATUSES, since);
        if (reservations.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> rank = new HashMap<>();
        for (int i = 0; i < reservations.size(); i++) {
            rank.put(reservations.get(i).getId(), i);
        }
        List<ReservedShipmentItem> visible = reservedShipmentItemRepository
                .findByReservedShipment_IdIn(rank.keySet()).stream()
                .filter(i -> OPEN_RESULTS.contains(i.getResult()) || !lastTime(i).isBefore(since))
                .sorted(Comparator.comparing((ReservedShipmentItem i) -> rank.get(i.getReservedShipment().getId()))
                        .thenComparing(ReservedShipmentItem::getId))
                .toList();
        return toRows(visible);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservedShipmentRowResponse> listByOrder(String externalOrderId) {
        return toRows(reservedShipmentItemRepository.findByExternalOrderIdOrderByIdDesc(externalOrderId).stream()
                .filter(i -> i.getReservedShipment() != null)              // D18 — 보관 행(STORED)은 실행 기록이 아니다
                .toList());
    }

    @Override
    @Transactional
    public InternalStageResult cancelItems(List<Long> orderItemIds) {
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(orderItemIds.stream().distinct().toList());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }
        Map<Long, String> orderIdByShipment = new LinkedHashMap<>();
        Set<String> allOrders = new LinkedHashSet<>();
        for (OrderLine line : lines) {
            allOrders.add(line.getOrder().getExternalOrderId());
            OrderShipment shipment = line.getOrderShipment();
            if (shipment != null && shipment.getInternalStage() == InternalShipmentStage.AWAITING_SHIPMENT) {
                orderIdByShipment.putIfAbsent(shipment.getId(), line.getOrder().getExternalOrderId());
            }
        }
        internalShipmentStageService.assertNotRunning(orderIdByShipment.keySet());

        List<ReservedShipmentItem> open = orderIdByShipment.isEmpty() ? List.of()
                : reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(
                        orderIdByShipment.keySet(), List.of(ReservedItemResult.PENDING, ReservedItemResult.FAILED));
        Set<Long> released = new LinkedHashSet<>();
        Set<Long> reservationIds = new LinkedHashSet<>();
        for (ReservedShipmentItem item : open) {
            reservedShipmentItemRepository.save(item.toBuilder()
                    .result(ReservedItemResult.RELEASED)
                    .failureReason("사용자가 예약을 취소했습니다")
                    .build());
            // D18 🔁 — 송장은 주문에 남는다: 같은 송장을 예약 없이 보관(STORED)하는 새 행.
            reservedShipmentItemRepository.save(item.toStored());
            released.add(item.getOrderShipment().getId());
            reservationIds.add(item.getReservedShipment().getId());
        }
        if (!released.isEmpty()) {
            orderShipmentRepository.setInternalStage(released, InternalShipmentStage.INTERNAL_PREPARING.name());
        }
        reservedShipmentSettler.settle(reservationIds);

        Set<String> changedOrders = new LinkedHashSet<>();
        released.forEach(id -> changedOrders.add(orderIdByShipment.get(id)));
        List<String> skipped = allOrders.stream().filter(o -> !changedOrders.contains(o)).toList();
        return new InternalStageResult(lines.size(), released.size(), skipped, List.of());
    }

    @Override
    @Transactional
    public ReservedShipmentRowResponse changeTime(Long itemId, LocalDateTime executeAt) {
        requireFuture(executeAt);
        ReservedShipment own = detach(editable(itemId));
        reservedShipmentRepository.save(own.toBuilder().executeAt(executeAt).nextRunAt(executeAt).build());
        return row(itemId);
    }

    @Override
    public ReservedShipmentRowResponse retry(Long itemId) {
        ReservedShipmentItem item = scopedItem(itemId);
        if (item.getResult() != ReservedItemResult.FAILED
                || item.getReservedShipment().getStatus() != ReservedShipmentStatus.STOPPED) {
            throw new IllegalArgumentException("자동 재시도가 멈춘 주문만 다시 시도할 수 있습니다");
        }
        ReservedShipment own = detach(item);
        reservedShipmentExecutor.run(own.getId(), ReservedShipmentStatus.STOPPED, ReservedRunKind.MANUAL)
                .orElseThrow(() -> new IllegalArgumentException(InternalShipmentStageServiceImpl.RUNNING_MESSAGE));
        return row(itemId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredInvoiceResponse> invoicesOfOrder(String externalOrderId) {
        Map<Long, OrderShipment> shipments = paidInternal(orderLineRepository.findByExternalOrderId(externalOrderId));
        Map<Long, ReservedShipmentItem> live = liveItems(shipments.keySet());
        return shipments.values().stream()
                .map(s -> StoredInvoiceResponse.of(s, live.get(s.getId())))
                .toList();
    }

    @Override
    @Transactional
    public StoredInvoiceResponse changeInvoice(Long orderShipmentId, String deliveryCompanyCode, String invoiceNumber) {
        String invoice = InvoiceNumbers.normalizeRequired(invoiceNumber);
        String carrierCode = carrierCodeService.validateDeliveryCompanyCode(deliveryCompanyCode, Platform.COUPANG);
        List<OrderLine> lines = orderLineRepository.findWithAccountByOrderShipment_IdIn(List.of(orderShipmentId));
        ReservedShipmentItem live = liveItems(List.of(orderShipmentId)).get(orderShipmentId);
        OrderShipment shipment = invoiceTarget(lines, orderShipmentId, live);
        if (shipment == null) {
            throw new IllegalArgumentException(NOT_INTERNAL_MESSAGE);
        }
        internalShipmentStageService.assertNotRunning(List.of(orderShipmentId));     // D18 — 막는 것은 실행 중뿐
        ReservedShipmentItem next = live != null
                ? live.toBuilder().carrierCode(carrierCode).invoiceNumbers(invoice).build()
                : ReservedShipmentItem.builder()
                        .orderShipment(shipment)
                        .externalOrderId(lines.get(0).getOrder().getExternalOrderId())
                        .carrierCode(carrierCode)
                        .invoiceNumbers(invoice)
                        .progress(ReservedItemProgress.NONE)
                        .result(ReservedItemResult.STORED)
                        .build();
        reservedShipmentItemRepository.save(next);
        return StoredInvoiceResponse.of(shipment, next);
    }

    @Override
    @Transactional
    public ReservationCreateResult reserveStored(List<Long> orderItemIds, LocalDateTime executeAt) {
        requireFuture(executeAt);
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(orderItemIds.stream().distinct().toList());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }
        ReservedShipment reservation = null;
        int reserved = 0;
        List<ExcludedOrder> excluded = new ArrayList<>();
        List<Long> toAwaiting = new ArrayList<>();
        for (Map.Entry<String, List<OrderLine>> entry : groupByOrder(lines).entrySet()) {
            String orderId = entry.getKey();
            Map<Long, OrderShipment> paid = paidInternal(entry.getValue());
            List<Long> internalIds = paid.values().stream()
                    .filter(s -> s.getInternalStage() == InternalShipmentStage.INTERNAL_PREPARING)
                    .map(OrderShipment::getId).toList();
            if (internalIds.isEmpty()) {
                excluded.add(new ExcludedOrder(orderId, paid.isEmpty() ? NOT_INTERNAL_MESSAGE : ALREADY_RESERVED_MESSAGE));
                continue;
            }
            Map<Long, ReservedShipmentItem> stored = liveItems(internalIds);
            List<ReservedShipmentItem> attach = internalIds.stream().map(stored::get).filter(Objects::nonNull).toList();
            if (attach.isEmpty()) {
                excluded.add(new ExcludedOrder(orderId, NO_STORED_INVOICE_MESSAGE));
                continue;
            }
            if (reservation == null) {
                reservation = newReservation(executeAt);
            }
            for (ReservedShipmentItem item : attach) {
                reservedShipmentItemRepository.save(item.toBuilder()
                        .reservedShipment(reservation)
                        .progress(ReservedItemProgress.NONE)
                        .result(ReservedItemResult.PENDING)
                        .failureReason(null)
                        .build());
                toAwaiting.add(item.getOrderShipment().getId());
                reserved++;
            }
        }
        if (!toAwaiting.isEmpty()) {
            orderShipmentRepository.setInternalStage(toAwaiting, InternalShipmentStage.AWAITING_SHIPMENT.name());
        }
        log.info("예약 발송 생성(저장 송장): reservation={} executeAt={} reserved={} excluded={}",
                reservation == null ? null : reservation.getId(), executeAt, reserved, excluded.size());
        return new ReservationCreateResult(reservation == null ? null : reservation.getId(), executeAt,
                reserved, 0, excluded);
    }

    @Override
    public ShipmentConfirmResult shipStoredNow(List<Long> orderItemIds) {
        List<OrderLine> lines = orderLineRepository.findWithAccountByIdIn(orderItemIds.stream().distinct().toList());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 라인을 찾을 수 없습니다");
        }
        Map<String, List<OrderLine>> linesByOrder = groupByOrder(lines);
        List<ReservedInvoice> invoices = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (Map.Entry<String, List<OrderLine>> entry : linesByOrder.entrySet()) {
            Map<Long, OrderShipment> shipments = paidInternal(entry.getValue());
            Map<Long, ReservedShipmentItem> live = liveItems(shipments.keySet());
            List<ReservedInvoice> mine = shipments.keySet().stream()
                    .filter(live::containsKey)
                    .map(id -> new ReservedInvoice(id, entry.getKey(), live.get(id).getCarrierCode(),
                            live.get(id).invoiceNumberList()))
                    .toList();
            if (mine.isEmpty()) {
                unmatched.add(entry.getKey());          // 내부 단계가 아니거나 저장된 송장이 없다
            } else {
                invoices.addAll(mine);
            }
        }
        ShipmentConfirmResult sent = shipmentConfirmService.shipStoredInvoices(invoices);
        return new ShipmentConfirmResult(linesByOrder.size(), sent.matchedOrders(), unmatched,
                sent.succeeded(), sent.failed(), sent.skipped());
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private void requireFuture(LocalDateTime executeAt) {
        if (executeAt == null) {
            throw new IllegalArgumentException("예약 시각을 입력하세요");
        }
        if (!executeAt.isAfter(LocalDateTime.now(SyncWindow.KST))) {
            throw new IllegalArgumentException(PAST_TIME_MESSAGE);
        }
    }

    private ReservedShipment newReservation(LocalDateTime executeAt) {
        return reservedShipmentRepository.save(ReservedShipment.builder()
                .executeAt(executeAt)
                .nextRunAt(executeAt)
                .status(ReservedShipmentStatus.SCHEDULED)
                .retryCount(0)
                .build());
    }

    /** D29 — 결제완료(PAID) 라인이 있고 내부 단계가 있는 배송 묶음(id → 묶음, 라인 순서). */
    private static Map<Long, OrderShipment> paidInternal(List<OrderLine> lines) {
        Map<Long, OrderShipment> shipments = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            OrderShipment shipment = line.getOrderShipment();
            if (line.getStatus() == OrderStatus.PAID && shipment != null && shipment.getInternalStage() != null) {
                shipments.putIfAbsent(shipment.getId(), shipment);
            }
        }
        return shipments;
    }

    /**
     * D18 · D29 예외 — 송장을 고칠 수 있는 배송 묶음(E12). 내부 단계가 있고, PAID 라인이 있거나({@link #paidInternal})
     * 현재 송장 행({@code live})이 열린 결과(PENDING·FAILED)면 그 묶음. ② 발주처리 성공 · ③ 실패 줄은 저장 status 가 PREPARING 이라
     * 둘째 조건으로 들어온다 — 실행기가 재시도 때 결과 행을 다시 읽으므로 고친 송장으로 ③ 을 보낸다. 아니면 null.
     */
    private static OrderShipment invoiceTarget(List<OrderLine> lines, Long orderShipmentId, ReservedShipmentItem live) {
        OrderShipment paid = paidInternal(lines).get(orderShipmentId);
        if (paid != null) {
            return paid;
        }
        if (live == null || !OPEN_RESULTS.contains(live.getResult())) {
            return null;
        }
        return lines.stream()
                .map(OrderLine::getOrderShipment)
                .filter(s -> s != null && orderShipmentId.equals(s.getId()) && s.getInternalStage() != null)
                .findFirst()
                .orElse(null);
    }

    /** D18 — 배송 묶음별 현재 송장 행 = 결과가 STORED·PENDING·FAILED 인 행 중 id 가 가장 큰 것. */
    private Map<Long, ReservedShipmentItem> liveItems(Collection<Long> orderShipmentIds) {
        Map<Long, ReservedShipmentItem> live = new HashMap<>();
        if (orderShipmentIds.isEmpty()) {
            return live;
        }
        for (ReservedShipmentItem item : reservedShipmentItemRepository
                .findByOrderShipment_IdInAndResultIn(orderShipmentIds, LIVE_RESULTS)) {
            live.merge(item.getOrderShipment().getId(), item, (a, b) -> a.getId() > b.getId() ? a : b);
        }
        return live;
    }

    private static Map<String, List<OrderLine>> groupByOrder(List<OrderLine> lines) {
        Map<String, List<OrderLine>> byOrder = new LinkedHashMap<>();
        lines.forEach(l -> byOrder.computeIfAbsent(l.getOrder().getExternalOrderId(), k -> new ArrayList<>()).add(l));
        return byOrder;
    }

    /** 실행 시작 전 = SCHEDULED 이고 한 번도 실행되지 않았다(D18 행3 「시각 변경」). */
    private boolean notStarted(ReservedShipment reservation) {
        return reservation.getStatus() == ReservedShipmentStatus.SCHEDULED && reservation.getFirstRunKind() == null;
    }

    /** 시각을 바꿀 수 있는 결과 행 — 예약이 실행 중이면 「처리 중」, 대기(PENDING)·실행 시작 전이 아니면 거절(D18 행3). */
    private ReservedShipmentItem editable(Long itemId) {
        ReservedShipmentItem item = scopedItem(itemId);
        ReservedShipment reservation = item.getReservedShipment();
        if (reservation.getStatus() == ReservedShipmentStatus.RUNNING) {
            throw new IllegalArgumentException(InternalShipmentStageServiceImpl.RUNNING_MESSAGE);
        }
        if (item.getResult() != ReservedItemResult.PENDING || !notStarted(reservation)) {
            throw new IllegalArgumentException(STARTED_MESSAGE);
        }
        return item;
    }

    /**
     * 이 결과 행(주문)만 따로 움직이도록 예약에서 떼어낸다(D30). 같은 예약에 다른 결과 행이 하나도 없으면(결과 무관)
     * 떼지 않고 그 예약을 그대로 돌려준다. 새 예약은 원래 예약의 값을 그대로 복사한다(id 만 새로).
     * 닫힌 형제 행(RELEASED 등)만 있어도 뗀다 — 형제 행의 예정 시각·7일 기준이 따라 움직이지 않게.
     */
    private ReservedShipment detach(ReservedShipmentItem item) {
        ReservedShipment reservation = item.getReservedShipment();
        boolean shared = reservedShipmentItemRepository.findByReservedShipment_Id(reservation.getId()).stream()
                .anyMatch(other -> !other.getId().equals(item.getId()));
        if (!shared) {
            return reservation;
        }
        ReservedShipment own = reservedShipmentRepository.save(reservation.toBuilder().id(null).build());
        reservedShipmentItemRepository.save(item.toBuilder().reservedShipment(own).build());
        reservedShipmentSettler.settle(List.of(reservation.getId()));   // 남은 행이 전부 끝났으면 원래 예약을 닫는다
        log.info("예약 발송 행 분리: item={} from={} to={}", item.getId(), reservation.getId(), own.getId());
        return own;
    }

    /** 끝난 행이 목록에 남는 기준 시각 — 그 행의 마지막 실행, 없으면 예약의 실행 시각(D30 「최근 7일」 · D28 행 단위 시각). */
    private static LocalDateTime lastTime(ReservedShipmentItem item) {
        return item.getLastRunAt() != null ? item.getLastRunAt() : item.getReservedShipment().getExecuteAt();
    }

    /** 택배사 코드 — 지금 활성 택배사(기존 일괄 발송처리와 같은 규칙). 미설정이면 500 이 아니라 400. */
    private String resolveCarrierCode() {
        try {
            return carrierCodeService.resolveDeliveryCompanyCode(Platform.COUPANG);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private String joinInvoices(String orderId, List<String> invoices) {
        if (invoices.size() > MAX_INVOICES_PER_ITEM) {
            log.warn("예약 송장 상한 초과 — 앞 {}장만 저장: orderId={} invoices={}",
                    MAX_INVOICES_PER_ITEM, orderId, invoices.size());
            return String.join(",", invoices.subList(0, MAX_INVOICES_PER_ITEM));
        }
        return String.join(",", invoices);
    }

    private ReservedShipmentItem scopedItem(Long itemId) {
        return reservedShipmentItemRepository.findScopedById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("ReservedShipmentItem", itemId));
    }

    private ReservedShipmentRowResponse row(Long itemId) {
        return toRows(List.of(scopedItem(itemId))).get(0);
    }

    /** 결과 행 → 응답 행. 라인 id(E7 [예약 취소] 입력)는 배송 묶음별로 한 번에 읽는다(N+1 금지). */
    private List<ReservedShipmentRowResponse> toRows(List<ReservedShipmentItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Set<Long> shipmentIds = new LinkedHashSet<>();
        items.forEach(i -> shipmentIds.add(i.getOrderShipment().getId()));
        Map<Long, List<Long>> lineIdsByShipment = new HashMap<>();
        for (OrderLine line : orderLineRepository.findWithAccountByOrderShipment_IdIn(shipmentIds)) {
            lineIdsByShipment.computeIfAbsent(line.getOrderShipment().getId(), k -> new ArrayList<>()).add(line.getId());
        }
        return items.stream()
                .map(i -> ReservedShipmentRowResponse.of(i,
                        lineIdsByShipment.getOrDefault(i.getOrderShipment().getId(), List.of())))
                .toList();
    }
}
