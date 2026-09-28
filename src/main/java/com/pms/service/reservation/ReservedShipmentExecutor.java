package com.pms.service.reservation;

import com.pms.domain.InternalShipmentStage;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.ReservedItemProgress;
import com.pms.domain.ReservedItemResult;
import com.pms.domain.ReservedRunKind;
import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentItem;
import com.pms.domain.ReservedShipmentStatus;
import com.pms.dto.request.OrderRefreshRequest;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ReservedShipmentItemRepository;
import com.pms.repository.ReservedShipmentRepository;
import com.pms.security.TenantContext;
import com.pms.service.OrderAcknowledgeService;
import com.pms.service.ReservationAckResult;
import com.pms.service.ReservedInvoice;
import com.pms.service.ReservedInvoiceResult;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmService;
import com.pms.service.coupang.OrderRefreshResult;
import com.pms.service.coupang.OrderRefreshService;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 예약 발송 실행기 (FEATURE_2609_75 / D3 · D15 · D16 · D17 · D18 · D19).
 *
 * <p>예약 1건 = 배송 묶음 N개를 ① 취소 재확인({@link OrderRefreshService}) → ② 발주처리
 * ({@link OrderAcknowledgeService#acknowledgeForReservation}) → ③ 송장 등록({@link ShipmentConfirmService#sendReservedInvoices})
 * 순으로 돈다. 세 단계 전부 기존 코드 경로다(D27) — 속도 제한도 기존 {@code CoupangApiClientImpl} 한도 그대로(D19).
 *
 * <p>🔴 재시도는 <b>실패한 단계부터</b>(D16): 결과 행의 {@code progress} = 마지막으로 성공한 단계. 발주처리가 성공한 묶음은
 * 다시 발주처리하지 않는다. 실패로 끝난 실행이 첫 실행 포함 3번(첫 실행 + 자동 재시도 2회)이면 STOPPED([다시 시도] 대기),
 * 아니면 20분 뒤 다시.
 * <p>쿠팡이 0박스를 돌려준 주문({@code OrderRefreshResult.empty})은 전량 취소로 보고 CANCELLED — 재시도하지 않는다(D17 ①).
 * <p>🔴 상태가 바뀐 묶음은 요청에서 뺀다 — 실패로 만들지 않는다(D16 🔁 × D18). ① 뒤 · ② 발주처리 직전 · ③ 송장 등록 직전마다
 * 로컬 DB 로 {@link ShipmentLocalState#judge} 를 다시 한다(재시도·[다시 시도]가 ①을 건너뛰고 ②·③부터 이어 해도 같다). 이미 상품준비중 → ② 건너뛰고 ③ ·
 * 발송 이후 → EXTERNAL · 전량 취소 → CANCELLED · 일부 수량 취소 → RELEASED + 「내부 상품준비중」(D17). 이 판정에 쿠팡 추가 호출은 없다
 * (주문 동기화가 로컬 DB 를 최신으로 유지한다).
 * <p>🔴 결과 행의 {@code lastRunAt} = 그 행이 이 실행에서 시도·종료된 시각(D28 · D30). 이 실행이 다루는 행(열린 결과)만 쓴다 —
 * 같은 예약의 이미 끝난 행은 조회하지 않으므로 시각이 바뀌지 않는다. 저장은 {@link #persist} 한 곳이다.
 * <p>⚠️ {@code @Transactional} 금지 — 외부 HTTP 를 도는 경로다. 저장은 리포지토리 호출마다 끝난다.
 * <p>⚠️ 결과 행 상태는 {@link Slot} 에 들고 다니고, 저장은 매번 <b>조회 때 로딩한 인스턴스</b>({@code orderShipment} 로딩됨)의
 * {@code toBuilder()} 로 한다 — {@code save()} 반환값은 연관이 지연 프록시라 트랜잭션 밖에서 박스 id 를 읽으면 터진다.
 * <p>🔴 {@link #inFlight} = 이 인스턴스에서 지금 실행 중인 예약 id. 스케줄러는 {@code ContextRefreshedEvent} 때 이미 돌아서,
 * 기동 직후 cron 이 가져간(RUNNING) 예약을 {@code ApplicationReadyEvent} 의 {@link #recoverOnStartup} 이 SCHEDULED 로
 * 되돌리면 같은 예약이 두 번 돈다(되돌릴 수 없는 발주처리·송장 등록 중복). 실행 중인 id 는 되돌리지도, 다시 가져가지도 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservedShipmentExecutor {

    static final int MAX_FAILED_RUNS = 3;
    static final long RETRY_INTERVAL_MINUTES = 20;
    public static final String PARTIAL_CANCEL_MESSAGE = "일부 수량이 취소되어 예약을 해제했습니다. 직접 처리하세요";
    /** {@code OrderRefreshServiceImpl.MAX_ORDERS} 와 같은 값 — 넘기면 그쪽이 400 을 던진다. */
    private static final int REFRESH_CHUNK_ORDERS = 50;
    private static final int REASON_MAX = 500;
    private static final Set<ReservedItemResult> OPEN =
            EnumSet.of(ReservedItemResult.PENDING, ReservedItemResult.FAILED);
    private static final Set<ReservedItemResult> DONE_RESULTS =
            EnumSet.of(ReservedItemResult.SUCCEEDED, ReservedItemResult.EXTERNAL, ReservedItemResult.CANCELLED);

    private final ReservedShipmentRepository reservedShipmentRepository;
    private final ReservedShipmentItemRepository reservedShipmentItemRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final OrderRefreshService orderRefreshService;
    private final OrderAcknowledgeService orderAcknowledgeService;
    private final ShipmentConfirmService shipmentConfirmService;

    /** 이 인스턴스가 뜬 시각(KST). 첫 실행인데 실행 시각이 이보다 이르면 서버가 꺼져 있던 동안 지난 예약이다(D15). */
    private final LocalDateTime startedAt = LocalDateTime.now(SyncWindow.KST);

    /** 이 인스턴스에서 지금 실행 중인 예약 id — 기동 복구와 cron 의 중복 실행 방지(서버 1대 전제, discussion §4). */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 스케줄 진입점 — 전 테넌트의 기한이 된 예약을 오래된 것부터 실행한다. */
    public void runDue() {
        for (Long tenantId : marketplaceAccountRepository.findDistinctTenantIds()) {
            try {
                TenantContext.set(tenantId);
                LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
                for (ReservedShipment due : reservedShipmentRepository
                        .findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(ReservedShipmentStatus.SCHEDULED, now)) {
                    ReservedRunKind kind = due.getExecuteAt().isBefore(startedAt)
                            ? ReservedRunKind.DELAYED : ReservedRunKind.ON_TIME;
                    try {
                        run(due.getId(), ReservedShipmentStatus.SCHEDULED, kind);
                    } catch (Exception e) {
                        log.warn("예약 발송 실행 실패: tenant={} reservation={}", tenantId, due.getId(), e);
                    }
                }
            } catch (Exception e) {
                log.warn("예약 발송 조회 실패: tenant={}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 기동 직후 1회 — 실행 중에 꺼진 예약을 SCHEDULED 로 되돌리고 곧바로 기한이 된 예약을 돈다(D15). */
    public void recoverOnStartup() {
        for (Long tenantId : marketplaceAccountRepository.findDistinctTenantIds()) {
            try {
                TenantContext.set(tenantId);
                for (ReservedShipment stuck : reservedShipmentRepository.findByStatus(ReservedShipmentStatus.RUNNING)) {
                    if (inFlight.contains(stuck.getId())) {
                        continue;                          // 기동 직후 cron 이 이미 돌고 있는 예약 — 되돌리지 않는다
                    }
                    reservedShipmentRepository.transition(stuck.getId(),
                            ReservedShipmentStatus.RUNNING, ReservedShipmentStatus.SCHEDULED);
                    log.warn("예약 발송 기동 복구: 실행 중에 멈춘 예약을 다시 예약 상태로: tenant={} reservation={}",
                            tenantId, stuck.getId());
                }
            } catch (Exception e) {
                log.warn("예약 발송 기동 복구 실패: tenant={}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
        runDue();
    }

    /**
     * 예약 1건 실행. {@code from} → RUNNING 전이에 성공한 호출만 실행한다.
     *
     * @return 실행 뒤 예약. 이미 다른 실행이 가져갔거나 상태가 {@code from} 이 아니면 empty
     */
    public Optional<ReservedShipment> run(Long reservationId, ReservedShipmentStatus from, ReservedRunKind kind) {
        if (!inFlight.add(reservationId)) {
            return Optional.empty();                       // 이 인스턴스에서 이미 실행 중
        }
        try {
            if (reservedShipmentRepository.transition(reservationId, from, ReservedShipmentStatus.RUNNING) == 0) {
                return Optional.empty();
            }
            return Optional.of(runClaimed(reservationId, kind));
        } finally {
            inFlight.remove(reservationId);
        }
    }

    /** RUNNING 전이에 성공한 예약 1건의 본문 — {@link #run} 만 부른다. */
    private ReservedShipment runClaimed(Long reservationId, ReservedRunKind kind) {
        ReservedShipment reservation = reservedShipmentRepository.findScopedById(reservationId).orElseThrow();
        LocalDateTime runAt = LocalDateTime.now(SyncWindow.KST);
        if (reservation.getFirstRunKind() == null) {
            reservation = reservation.toBuilder().firstRunKind(kind).build();
            reservedShipmentRepository.save(reservation);
        }

        List<Slot> slots = new ArrayList<>();
        for (ReservedShipmentItem item : reservedShipmentItemRepository
                .findByReservedShipment_IdAndResultIn(reservationId, OPEN)) {
            Slot slot = new Slot(item, runAt);
            if (slot.result == ReservedItemResult.FAILED) {
                slot.result = ReservedItemResult.PENDING;      // 이번 실행에서 다시 시도한다 — 단계는 그대로(D16)
                slot.reason = null;
                persist(slot);
            }
            slots.add(slot);
        }
        try {
            checkCancellation(slots);
            acknowledge(slots);
            registerInvoices(slots);
        } catch (Exception e) {
            log.warn("예약 발송 단계 중단: reservation={}", reservationId, e);
            for (Slot slot : slots) {
                if (slot.result == ReservedItemResult.PENDING) {
                    fail(slot, "처리 중 오류: " + e.getMessage());
                }
            }
        }
        return finish(reservation, slots, runAt);
    }

    // ── ① 취소 재확인 (progress NONE) ─────────────────────────────────────

    private void checkCancellation(List<Slot> slots) {
        List<Slot> targets = pending(slots, ReservedItemProgress.NONE);
        if (targets.isEmpty()) {
            return;
        }
        Set<Long> shipmentIds = shipmentIds(targets);
        Map<String, String> failedOrders = new HashMap<>();
        Set<String> emptyOrders = new HashSet<>();
        refresh(orderLineRepository.findWithAccountByOrderShipment_IdIn(shipmentIds), failedOrders, emptyOrders);
        Map<Long, List<OrderLine>> linesByShipment =
                groupByShipment(orderLineRepository.findWithAccountByOrderShipment_IdIn(shipmentIds));

        List<Long> toClear = new ArrayList<>();
        List<Long> toInternal = new ArrayList<>();
        for (Slot slot : targets) {
            Long shipmentId = slot.origin.getOrderShipment().getId();
            String orderId = slot.origin.getExternalOrderId();
            List<OrderLine> lines = linesByShipment.getOrDefault(shipmentId, List.of());
            if (failedOrders.containsKey(orderId)) {
                fail(slot, "취소 재확인 실패: " + failedOrders.get(orderId));
                continue;
            }
            if (emptyOrders.contains(orderId)) {
                end(slot, ReservedItemResult.CANCELLED, null);                    // D17 ① 0박스 = 전량 취소
                toClear.add(shipmentId);
                continue;
            }
            if (lines.isEmpty()) {
                fail(slot, "주문 라인을 찾을 수 없습니다");
                continue;
            }
            ShipmentLocalState local = ShipmentLocalState.judge(lines);
            if (closeIfDone(slot, local, toClear) || releaseIfPartial(slot, local, toInternal)) {
                continue;                                        // D17 전량·일부 취소 · D18 WING 에서 이미 발송
            }
            if (local == ShipmentLocalState.PREPARING) {
                advance(slot, ReservedItemProgress.ACKNOWLEDGED);                // D18 WING 에서 이미 발주처리
            } else if (local == ShipmentLocalState.PAID) {
                advance(slot, ReservedItemProgress.CANCEL_CHECKED);
            } else {
                fail(slot, "주문 상태를 확인할 수 없습니다");
            }
        }
        clearStage(toClear);
        backToInternal(toInternal);
    }

    /** 주문번호 50건씩 쿠팡 단건 재조회(기존 주문 상태 갱신과 같은 경로). 실패·0박스 주문을 모은다. */
    private void refresh(List<OrderLine> lines, Map<String, String> failedOrders, Set<String> emptyOrders) {
        Map<String, Long> anyLineByOrder = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            anyLineByOrder.putIfAbsent(line.getOrder().getExternalOrderId(), line.getId());
        }
        List<String> orderIds = new ArrayList<>(anyLineByOrder.keySet());
        for (int from = 0; from < orderIds.size(); from += REFRESH_CHUNK_ORDERS) {
            List<String> chunk = orderIds.subList(from, Math.min(from + REFRESH_CHUNK_ORDERS, orderIds.size()));
            try {
                OrderRefreshResult result = orderRefreshService.refresh(
                        new OrderRefreshRequest(chunk.stream().map(anyLineByOrder::get).toList()));
                result.failed().forEach(f -> failedOrders.put(f.externalOrderId(), f.reason()));
                emptyOrders.addAll(result.empty());
            } catch (Exception e) {
                chunk.forEach(orderId -> failedOrders.put(orderId, e.getMessage()));
            }
        }
    }

    // ── ② 발주처리 (progress CANCEL_CHECKED) ──────────────────────────────

    private void acknowledge(List<Slot> slots) {
        List<Slot> targets = pending(slots, ReservedItemProgress.CANCEL_CHECKED);
        if (targets.isEmpty()) {
            return;
        }
        // D16 × D18 — 보내기 직전에 로컬 DB 로 다시 판정한다. 결제완료(PAID)로 남은 묶음만 보낸다.
        Map<Long, List<OrderLine>> linesByShipment =
                groupByShipment(orderLineRepository.findWithAccountByOrderShipment_IdIn(shipmentIds(targets)));
        List<Long> toClear = new ArrayList<>();
        List<Long> toInternal = new ArrayList<>();
        List<Slot> toSend = new ArrayList<>();
        List<Long> lineIds = new ArrayList<>();
        for (Slot slot : targets) {
            List<OrderLine> lines = linesByShipment.getOrDefault(slot.origin.getOrderShipment().getId(), List.of());
            if (lines.isEmpty()) {
                fail(slot, "주문 라인을 찾을 수 없습니다");
                continue;
            }
            ShipmentLocalState local = ShipmentLocalState.judge(lines);
            if (closeIfDone(slot, local, toClear) || releaseIfPartial(slot, local, toInternal)) {
                continue;
            }
            if (local == ShipmentLocalState.PREPARING) {
                advance(slot, ReservedItemProgress.ACKNOWLEDGED);                // D18 6행 — ② 건너뛰고 ③
            } else if (local == ShipmentLocalState.PAID) {
                toSend.add(slot);
                lines.stream().filter(l -> l.getStatus() == OrderStatus.PAID).forEach(l -> lineIds.add(l.getId()));
            } else {
                fail(slot, "주문 상태를 확인할 수 없습니다");
            }
        }
        clearStage(toClear);
        backToInternal(toInternal);
        if (toSend.isEmpty()) {
            return;
        }
        ReservationAckResult ack;
        try {
            ack = orderAcknowledgeService.acknowledgeForReservation(lineIds);
        } catch (Exception e) {
            toSend.forEach(slot -> fail(slot, "발주처리 실패: " + e.getMessage()));
            return;
        }
        Map<String, FailedBox> failedByBox = new HashMap<>();
        ack.failed().forEach(f -> failedByBox.putIfAbsent(f.shipmentBoxId(), f));
        for (Slot slot : toSend) {
            String boxId = slot.origin.getOrderShipment().getExternalShipmentId();
            if (ack.succeededBoxIds().contains(boxId)) {
                advance(slot, ReservedItemProgress.ACKNOWLEDGED);
            } else if (failedByBox.containsKey(boxId)) {
                FailedBox box = failedByBox.get(boxId);
                fail(slot, "발주처리 실패: " + box.resultCode() + " " + box.message());
            } else {
                fail(slot, "발주처리 결과를 받지 못했습니다");
            }
        }
    }

    // ── ③ 송장 등록 (progress ACKNOWLEDGED) ───────────────────────────────

    private void registerInvoices(List<Slot> slots) {
        List<Slot> targets = pending(slots, ReservedItemProgress.ACKNOWLEDGED);
        if (targets.isEmpty()) {
            return;
        }
        // D16 × D18 — 보내기 직전에 로컬 DB 로 다시 판정한다. 전량 취소·발송 이후·일부 수량 취소만 빼고 나머지는 보낸다.
        Map<Long, List<OrderLine>> linesByShipment =
                groupByShipment(orderLineRepository.findWithAccountByOrderShipment_IdIn(shipmentIds(targets)));
        List<Long> toClear = new ArrayList<>();
        List<Long> toInternal = new ArrayList<>();
        List<Slot> toSend = new ArrayList<>();
        for (Slot slot : targets) {
            List<OrderLine> lines = linesByShipment.getOrDefault(slot.origin.getOrderShipment().getId(), List.of());
            if (lines.isEmpty()) {
                fail(slot, "주문 라인을 찾을 수 없습니다");
                continue;
            }
            ShipmentLocalState local = ShipmentLocalState.judge(lines);
            if (!closeIfDone(slot, local, toClear) && !releaseIfPartial(slot, local, toInternal)) {
                toSend.add(slot);
            }
        }
        backToInternal(toInternal);
        if (toSend.isEmpty()) {
            clearStage(toClear);
            return;
        }
        List<ReservedInvoice> invoices = toSend.stream()
                .map(slot -> new ReservedInvoice(slot.origin.getOrderShipment().getId(),
                        slot.origin.getExternalOrderId(), slot.origin.getCarrierCode(),
                        slot.origin.invoiceNumberList()))
                .toList();
        ReservedInvoiceResult result;
        try {
            result = shipmentConfirmService.sendReservedInvoices(invoices);
        } catch (Exception e) {
            toSend.forEach(slot -> fail(slot, "송장 등록 실패: " + e.getMessage()));
            clearStage(toClear);
            return;
        }
        for (Slot slot : toSend) {
            Long shipmentId = slot.origin.getOrderShipment().getId();
            if (result.succeededShipmentIds().contains(shipmentId)) {
                slot.progress = ReservedItemProgress.INVOICED;
                end(slot, ReservedItemResult.SUCCEEDED, null);
                toClear.add(shipmentId);
            } else {
                fail(slot, "송장 등록 실패: " + result.failedReasons().getOrDefault(shipmentId, "결과를 받지 못했습니다"));
            }
        }
        clearStage(toClear);
    }

    // ── 마감 ─────────────────────────────────────────────────────────────

    private ReservedShipment finish(ReservedShipment reservation, List<Slot> slots, LocalDateTime runAt) {
        Set<Long> processed = new HashSet<>();
        List<ReservedItemResult> results = new ArrayList<>();
        for (Slot slot : slots) {
            processed.add(slot.origin.getId());
            results.add(slot.result);
        }
        reservedShipmentItemRepository.findByReservedShipment_Id(reservation.getId()).stream()
                .filter(i -> !processed.contains(i.getId()))
                .forEach(i -> results.add(i.getResult()));

        ReservedShipment.ReservedShipmentBuilder next = reservation.toBuilder().lastRunAt(runAt);
        if (results.stream().anyMatch(OPEN::contains)) {
            int retry = reservation.getRetryCount() + 1;
            next.retryCount(retry);
            if (retry >= MAX_FAILED_RUNS) {
                next.status(ReservedShipmentStatus.STOPPED);
            } else {
                next.status(ReservedShipmentStatus.SCHEDULED).nextRunAt(runAt.plusMinutes(RETRY_INTERVAL_MINUTES));
            }
        } else {
            next.status(results.stream().anyMatch(DONE_RESULTS::contains)
                    ? ReservedShipmentStatus.DONE : ReservedShipmentStatus.CANCELLED);
        }
        ReservedShipment done = next.build();
        reservedShipmentRepository.save(done);
        log.info("예약 발송 실행 끝: reservation={} status={} retry={} items={}",
                done.getId(), done.getStatus(), done.getRetryCount(), slots.size());
        return done;
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    /**
     * 실행 한 번 동안의 결과 행 상태. {@code origin} = 조회 때 로딩한 인스턴스(orderShipment 로딩됨).
     * {@code runAt} = 이 실행의 시작 시각(KST) — 저장할 때 그 행의 {@code lastRunAt} 이 된다(D28 · D30).
     */
    private static final class Slot {
        final ReservedShipmentItem origin;
        final LocalDateTime runAt;
        ReservedItemProgress progress;
        ReservedItemResult result;
        String reason;

        Slot(ReservedShipmentItem origin, LocalDateTime runAt) {
            this.origin = origin;
            this.runAt = runAt;
            this.progress = origin.getProgress();
            this.result = origin.getResult();
            this.reason = origin.getFailureReason();
        }
    }

    private List<Slot> pending(List<Slot> slots, ReservedItemProgress progress) {
        return slots.stream()
                .filter(s -> s.result == ReservedItemResult.PENDING && s.progress == progress)
                .toList();
    }

    private Set<Long> shipmentIds(List<Slot> slots) {
        Set<Long> ids = new LinkedHashSet<>();
        slots.forEach(s -> ids.add(s.origin.getOrderShipment().getId()));
        return ids;
    }

    private Map<Long, List<OrderLine>> groupByShipment(List<OrderLine> lines) {
        Map<Long, List<OrderLine>> byShipment = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            if (line.getOrderShipment() != null) {
                byShipment.computeIfAbsent(line.getOrderShipment().getId(), k -> new ArrayList<>()).add(line);
            }
        }
        return byShipment;
    }

    /**
     * 이미 끝난 묶음을 닫는다 — 전량 취소 → CANCELLED(D17) · 발송 이후 → EXTERNAL(D18 7행). 닫았으면 true.
     * 실패(FAILED)로 만들지 않는다(D16 🔁). 닫은 묶음은 {@code toClear} 에 담아 내부 단계를 비운다.
     */
    private boolean closeIfDone(Slot slot, ShipmentLocalState local, List<Long> toClear) {
        if (local != ShipmentLocalState.CANCELLED && local != ShipmentLocalState.EXTERNAL) {
            return false;
        }
        end(slot, local == ShipmentLocalState.CANCELLED ? ReservedItemResult.CANCELLED : ReservedItemResult.EXTERNAL, null);
        toClear.add(slot.origin.getOrderShipment().getId());
        return true;
    }

    /**
     * 일부 수량 취소 묶음을 예약에서 뺀다 — RELEASED + 사유 {@link #PARTIAL_CANCEL_MESSAGE}, 묶음은 {@code toInternal} 에 담아
     * 「내부 상품준비중」 으로 되돌린다(D17 · D16 🔁 — ① 뒤 · ② 직전 · ③ 직전 모두). 사용자가 직접 처리한다. 뺐으면 true.
     * 실패(FAILED)로 만들지 않고, 보관 송장(STORED)도 만들지 않는다.
     */
    private boolean releaseIfPartial(Slot slot, ShipmentLocalState local, List<Long> toInternal) {
        if (local != ShipmentLocalState.PARTIALLY_CANCELLED) {
            return false;
        }
        end(slot, ReservedItemResult.RELEASED, PARTIAL_CANCEL_MESSAGE);
        toInternal.add(slot.origin.getOrderShipment().getId());
        return true;
    }

    private void clearStage(List<Long> shipmentIds) {
        if (!shipmentIds.isEmpty()) {
            orderShipmentRepository.clearInternalStage(shipmentIds);
        }
    }

    private void backToInternal(List<Long> shipmentIds) {
        if (!shipmentIds.isEmpty()) {
            orderShipmentRepository.setInternalStage(shipmentIds, InternalShipmentStage.INTERNAL_PREPARING.name());
        }
    }

    private void advance(Slot slot, ReservedItemProgress progress) {
        slot.progress = progress;
        persist(slot);
    }

    private void fail(Slot slot, String reason) {
        slot.result = ReservedItemResult.FAILED;
        slot.reason = reason;
        persist(slot);
    }

    private void end(Slot slot, ReservedItemResult result, String reason) {
        slot.result = result;
        slot.reason = reason;
        persist(slot);
    }

    private void persist(Slot slot) {
        String reason = (slot.reason == null || slot.reason.length() <= REASON_MAX)
                ? slot.reason : slot.reason.substring(0, REASON_MAX);
        reservedShipmentItemRepository.save(slot.origin.toBuilder()
                .progress(slot.progress)
                .result(slot.result)
                .failureReason(reason)
                .lastRunAt(slot.runAt)
                .build());
    }
}
