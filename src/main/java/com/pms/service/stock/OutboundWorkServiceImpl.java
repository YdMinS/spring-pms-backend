package com.pms.service.stock;

import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.OutboundWorkRequest;
import com.pms.dto.request.StockMovementRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

import static com.pms.domain.StockReason.DAMAGED;
import static com.pms.domain.StockReason.ETC;
import static com.pms.domain.StockReason.EXPIRED;
import static com.pms.domain.StockReason.INTERNAL_USE;
import static com.pms.domain.StockReason.LOST;
import static com.pms.domain.StockReason.SAMPLE;
import static com.pms.domain.StockReason.SUPPLIER_RETURN;

/** {@link OutboundWorkService} implementation — the ledger rules stay in {@link StockLedgerServiceImpl} (D34). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OutboundWorkServiceImpl implements OutboundWorkService {

    /** The disposal reasons plus 「매입처 반품」 — 「교환 발송」 has its own save (D40). */
    private static final Set<StockReason> REASONS =
            EnumSet.of(DAMAGED, EXPIRED, LOST, SAMPLE, INTERNAL_USE, ETC, SUPPLIER_RETURN);

    private final WorkLineRecorder workLineRecorder;

    @Override
    @Transactional
    public void save(OutboundWorkRequest request) {
        workLineRecorder.requireStockLinked();
        // Check every line before writing any.
        for (OutboundWorkRequest.Line line : request.lines()) {
            if (!REASONS.contains(line.reason())) {
                throw new IllegalArgumentException("출고 작업 사유는 폐기 사유와 매입처 반품만 고를 수 있습니다.");
            }
        }
        for (OutboundWorkRequest.Line line : request.lines()) {
            workLineRecorder.record(new StockMovementRequest(
                    line.productId(),              // productId
                    line.sellerId(),               // sellerId
                    StockMovementType.DISPOSAL,    // movementType
                    line.quantity(),               // quantity — unsigned, the ledger flips the sign
                    line.reason(),                 // reason
                    line.reasonNote(),             // reasonNote
                    null,                          // unitPrice
                    null,                          // orderClaimId
                    null,                          // purchaseRecordId
                    request.movedOn()));           // movedOn
        }
    }
}
