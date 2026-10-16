package com.pms.service.claim;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.CollectInvoiceSource;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 11st return / exchange rows → {@code order_claim} with {@code platform = ELEVENST} (FEATURE_2610_17 / D15).
 * The Coupang {@link ClaimUpserter} stays Coupang-only — this is its 11st counterpart.
 *
 * <ul>
 *   <li>Key = UNIQUE (account, type, {@code clmReqSeq}, {@code ordPrdSeq}); {@code external_box_id} stays empty.</li>
 *   <li>Order line = the 11st mirror (account, {@code ordNo}, {@code ordPrdSeq}); linked once, then kept.</li>
 *   <li>Pickup invoice: an 11st value overwrites ours only when 11st has one (same rule as Coupang, 2609_70 D13).</li>
 *   <li>{@code STALE} (local close) is not reopened by an open 11st status.</li>
 *   <li>No pickup / exchange address, phone or member id is stored (D43).</li>
 * </ul>
 * ⚠️ {@code REQUIRES_NEW}: one row failing does not roll back the rest of the round.
 */
@Service
@RequiredArgsConstructor
public class ElevenstClaimUpserter {

    private final OrderClaimRepository orderClaimRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(MarketplaceAccount account, ClaimType type, ClaimRecord record) {
        Optional<OrderClaim> found = orderClaimRepository
                .findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                        account.getId(), type, record.externalClaimId(), record.externalItemId());
        LocalDateTime now = LocalDateTime.now();
        if (found.isEmpty()) {
            orderClaimRepository.save(OrderClaim.builder()
                    .marketplaceAccount(account)
                    .platform(Platform.ELEVENST)
                    .claimType(type)
                    .externalClaimId(record.externalClaimId())
                    .externalOrderId(record.externalOrderId())
                    .externalItemId(record.externalItemId())
                    .orderLine(matchOrderLine(account, record))
                    .orderItemMatchAttempts(0)
                    .itemName(record.itemName())
                    .quantity(record.quantity())
                    .status(record.status())
                    .platformStatus(record.platformStatus())
                    .reasonCode(record.reasonCode())
                    .reasonText(record.reasonText())
                    .collectInvoiceNo(record.collectInvoiceNo())
                    .collectCarrierCode(record.collectCarrierCode())
                    .collectInvoiceSource(record.collectInvoiceNo() != null ? CollectInvoiceSource.PLATFORM : null)
                    .receivedAt(record.receivedAt())
                    .syncedAt(now)
                    .build());
            return;
        }
        OrderClaim existing = found.get();
        if (existing.getStatus() == ClaimStatus.STALE && record.status().isOpen()) {
            return;
        }
        OrderLine orderLine = existing.getOrderLine() != null ? existing.getOrderLine() : matchOrderLine(account, record);
        orderClaimRepository.save(existing.toBuilder()
                .orderLine(orderLine)
                .itemName(record.itemName())
                .quantity(record.quantity())
                .status(record.status())
                .platformStatus(record.platformStatus())
                .reasonCode(record.reasonCode())
                .reasonText(record.reasonText())
                .collectInvoiceNo(record.collectInvoiceNo() != null
                        ? record.collectInvoiceNo() : existing.getCollectInvoiceNo())
                .collectCarrierCode(record.collectCarrierCode() != null
                        ? record.collectCarrierCode() : existing.getCollectCarrierCode())
                .collectInvoiceSource(record.collectInvoiceNo() != null
                        ? CollectInvoiceSource.PLATFORM : existing.getCollectInvoiceSource())
                .syncedAt(now)
                .build());
    }

    private OrderLine matchOrderLine(MarketplaceAccount account, ClaimRecord record) {
        return elevenstOrderLineRepository
                .findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(account.getId(), record.externalOrderId(),
                        record.externalItemId())
                .map(ElevenstOrderLine::getOrderLine)
                .orElse(null);
    }
}
