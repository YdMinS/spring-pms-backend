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
     * 2단 매칭 (D22): ① boxId 가 있으면 4키 → ② 실패하거나 boxId 가 없으면 3키.
     * 3키가 2건 이상이면 합포장으로 모호하므로 <b>연결하지 않는다</b> — 틀린 라인에 붙이느니 미연결이 낫다.
     */
    private OrderItem matchOrderItem(MarketplaceAccount account, ClaimRecord record) {
        if (record.externalBoxId() != null) {
            Optional<OrderItem> exact = orderItemRepository
                    .findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                            account.getId(), record.externalBoxId(),
                            record.externalOrderId(), record.externalItemId());
            if (exact.isPresent()) {
                return exact.get();
            }
        }

        List<OrderItem> candidates = orderItemRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndExternalItemId(
                        account.getId(), record.externalOrderId(), record.externalItemId());
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            log.debug("Ambiguous order line for claim (multi-box shipment): account={} orderId={} itemId={} matches={}",
                    account.getId(), record.externalOrderId(), record.externalItemId(), candidates.size());
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
