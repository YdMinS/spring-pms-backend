package com.pms.service.claim;

import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ClaimRecord} → {@code order_claim} 멱등 upsert + 주문 라인 2단 매칭 (FEATURE_2609_18).
 *
 * ⚠️ {@code REQUIRES_NEW} — 호출자({@link com.pms.service.coupang.CoupangReturnSyncServiceImpl})가
 * 클래스 레벨 {@code @Transactional} 이라, 합류 트랜잭션이면 적재 실패가 취소 보정까지 롤백시킨다.
 * 호출부의 try/catch 는 예외 전파만 막을 뿐 rollback-only 마킹은 못 막는다
 * ({@code SyncStatusWriter} 가 같은 이유로 REQUIRES_NEW 인 것과 동형).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimUpserter {

    private final OrderClaimRepository orderClaimRepository;
    private final OrderItemRepository orderItemRepository;

    /**
     * 클레임 1라인을 저장한다. UNIQUE(account, claimId, itemId) 로 멱등이며,
     * 값이 하나도 바뀌지 않으면 {@code save} 를 호출하지 않는다(불필요한 UPDATE·modified_date 갱신 방지).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(MarketplaceAccount account, ClaimType type, ClaimRecord record) {
        Optional<OrderClaim> found = orderClaimRepository
                .findByMarketplaceAccount_IdAndExternalClaimIdAndExternalItemId(
                        account.getId(), record.externalClaimId(), record.externalItemId());
        LocalDateTime now = LocalDateTime.now();

        if (found.isEmpty()) {
            orderClaimRepository.save(OrderClaim.builder()
                    .marketplaceAccount(account)
                    .platform("COUPANG")
                    .claimType(type)
                    .externalClaimId(record.externalClaimId())
                    .externalOrderId(record.externalOrderId())
                    .externalBoxId(record.externalBoxId())
                    .externalItemId(record.externalItemId())
                    .orderItem(matchOrderItem(account, record))
                    .orderItemMatchAttempts(0)          // 04(백필)가 증가시킨다
                    .itemName(record.itemName())
                    .quantity(record.quantity())
                    .status(record.status())
                    .platformStatus(record.platformStatus())
                    .reasonCode(record.reasonCode())
                    .reasonText(record.reasonText())
                    .faultType(record.faultType())
                    .returnShippingCharge(record.returnShippingCharge())
                    .collectInvoiceNo(record.collectInvoiceNo())
                    .collectCarrierCode(record.collectCarrierCode())
                    .requesterName(record.requesterName())
                    .receivedAt(record.receivedAt())
                    .platformModifiedAt(record.platformModifiedAt())
                    .syncedAt(now)
                    .build());
            return;
        }

        OrderClaim existing = found.get();
        // 이미 주문 라인이 붙어 있으면 재매칭하지 않는다 (D22) — 04 백필이 붙인 결과도 보존된다.
        OrderItem orderItem = existing.getOrderItem() != null
                ? existing.getOrderItem()
                : matchOrderItem(account, record);

        if (!hasChanges(existing, record, orderItem)) {
            return;
        }

        // receivedAt·externalClaimId 는 불변 — 추적 슬라이스(05)의 기준이라 흔들리면 안 된다.
        orderClaimRepository.save(existing.toBuilder()
                .orderItem(orderItem)
                .externalBoxId(record.externalBoxId() != null ? record.externalBoxId() : existing.getExternalBoxId())
                .itemName(record.itemName())
                .quantity(record.quantity())
                .status(record.status())
                .platformStatus(record.platformStatus())
                .reasonCode(record.reasonCode())
                .reasonText(record.reasonText())
                .faultType(record.faultType())
                .returnShippingCharge(record.returnShippingCharge())
                .collectInvoiceNo(record.collectInvoiceNo())
                .collectCarrierCode(record.collectCarrierCode())
                .requesterName(record.requesterName())
                .platformModifiedAt(record.platformModifiedAt())
                .syncedAt(now)
                .build());
    }

    /**
     * 미연결 클레임 1건의 재매칭 시도(04 백필). 연결되면 저장하고 {@code true},
     * 실패하면 시도횟수만 올리고 {@code false}.
     *
     * ⚠️ 호출 전에 그 주문이 {@code order_item} 에 적재돼 있어야 한다 — 이 메서드는 쿠팡을 치지 않는다.
     * ⚠️ 시도횟수를 올리는 주체는 하나다. {@code relink} 를 부른 claim 에 {@link #recordMatchAttempt}
     *    를 또 부르면 한 회차에 2 증가해 3회 상한이 실질 1.5회가 된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean relink(Long claimId) {
        Optional<OrderClaim> found = orderClaimRepository.findById(claimId);
        if (found.isEmpty()) {
            return false;
        }
        OrderClaim claim = found.get();
        if (claim.getOrderItem() != null) {
            return true;                    // 동시 실행 방어 — 이미 붙었으면 아무것도 하지 않는다
        }

        OrderItem matched = matchOrderItem(claim.getMarketplaceAccount().getId(),
                claim.getExternalOrderId(), claim.getExternalBoxId(), claim.getExternalItemId());
        if (matched == null) {
            // syncedAt 은 건드리지 않는다 — "쿠팡에서 갱신됐다"는 뜻이라 의미가 다르다.
            orderClaimRepository.save(claim.toBuilder()
                    .orderItemMatchAttempts(nextAttempts(claim))
                    .build());
            return false;
        }

        orderClaimRepository.save(claim.toBuilder().orderItem(matched).build());
        return true;
    }

    /** 주문조회 자체가 실패해 재매칭까지 가지 못한 경우 — 시도만 기록한다(포기 조건 D13). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMatchAttempt(Long claimId) {
        orderClaimRepository.findById(claimId).ifPresent(claim -> {
            if (claim.getOrderItem() != null) {
                return;                     // 이미 붙은 건은 포기 카운터를 소모시키지 않는다
            }
            orderClaimRepository.save(claim.toBuilder()
                    .orderItemMatchAttempts(nextAttempts(claim))
                    .build());
        });
    }

    /** 01 이 만든 기존 행은 attempts 가 null 일 수 있다(컬럼 추가 시점의 백필 없음). */
    private int nextAttempts(OrderClaim claim) {
        return (claim.getOrderItemMatchAttempts() == null ? 0 : claim.getOrderItemMatchAttempts()) + 1;
    }

    private OrderItem matchOrderItem(MarketplaceAccount account, ClaimRecord record) {
        return matchOrderItem(account.getId(), record.externalOrderId(),
                record.externalBoxId(), record.externalItemId());
    }

    /**
     * 2단 매칭 (D22): ① boxId 가 있으면 4키 → ② 실패하거나 boxId 가 없으면 3키.
     * 3키가 2건 이상이면 합포장으로 모호하므로 <b>연결하지 않는다</b> — 틀린 라인에 붙이느니 미연결이 낫다.
     *
     * ⚠️ 매칭 규칙의 단일 소유자다. 백필(04)도 {@link #relink} 를 통해 여기로 들어온다 —
     * {@code ClaimRecord} 를 재조립해 규칙을 복제하지 말 것(필드 대부분이 null 인 가짜 레코드가 된다).
     */
    private OrderItem matchOrderItem(Long accountId, String externalOrderId,
                                     String externalBoxId, String externalItemId) {
        if (externalBoxId != null) {
            Optional<OrderItem> exact = orderItemRepository
                    .findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                            accountId, externalBoxId, externalOrderId, externalItemId);
            if (exact.isPresent()) {
                return exact.get();
            }
        }

        List<OrderItem> candidates = orderItemRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(
                        accountId, externalOrderId, externalItemId);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            log.debug("Ambiguous order line for claim (multi-box shipment): account={} orderId={} itemId={} matches={}",
                    accountId, externalOrderId, externalItemId, candidates.size());
        }
        return null;
    }

    /** 갱신 대상 필드 중 하나라도 달라졌는가 — syncedAt 만 바꾸려고 UPDATE 를 쏘지 않기 위한 판정. */
    private boolean hasChanges(OrderClaim existing, ClaimRecord record, OrderItem orderItem) {
        return !Objects.equals(existing.getOrderItem(), orderItem)
                || (record.externalBoxId() != null && !Objects.equals(existing.getExternalBoxId(), record.externalBoxId()))
                || !Objects.equals(existing.getItemName(), record.itemName())
                || !Objects.equals(existing.getQuantity(), record.quantity())
                || existing.getStatus() != record.status()
                || !Objects.equals(existing.getPlatformStatus(), record.platformStatus())
                || !Objects.equals(existing.getReasonCode(), record.reasonCode())
                || !Objects.equals(existing.getReasonText(), record.reasonText())
                || !Objects.equals(existing.getFaultType(), record.faultType())
                || !Objects.equals(existing.getReturnShippingCharge(), record.returnShippingCharge())
                || !Objects.equals(existing.getCollectInvoiceNo(), record.collectInvoiceNo())
                || !Objects.equals(existing.getCollectCarrierCode(), record.collectCarrierCode())
                || !Objects.equals(existing.getRequesterName(), record.requesterName())
                || !Objects.equals(existing.getPlatformModifiedAt(), record.platformModifiedAt());
    }
}
