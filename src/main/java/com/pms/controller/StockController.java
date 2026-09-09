package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.PurchaseCandidateView;
import com.pms.dto.response.ReturnCandidateView;
import com.pms.dto.response.StockBalanceView;
import com.pms.dto.response.StockMovementView;
import com.pms.service.stock.StockLedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Physical stock ledger API — ADMIN only (FEATURE_2609_28 / PLAN D19).
 *
 * <p>⚠️ Path is {@code /api/admin/stock}, not {@code /api/stock}: the legacy {@code StockLog}
 * feature still owns the latter. This path stays even after that one is removed.
 *
 * <p>⚠️ This controller is the <b>only</b> caller of {@link StockLedgerService} (D18). Nothing
 * automatic may write to the ledger.
 *
 * @see StockLedgerService
 */
@RestController
@RequestMapping("/api/admin/stock")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StockController {

    private final StockLedgerService stockLedgerService;

    /** Check-in / return check-in / disposal / adjustment. Outbound is not accepted here. */
    @PostMapping("/movements")
    public ResponseEntity<ResponseDTO<StockMovementView>> record(
            @Valid @RequestBody StockMovementRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(stockLedgerService.record(request)));
    }

    /** On-hand per product (ledger sum). keyword = product name partial match. */
    @GetMapping("/balances")
    public ResponseEntity<ResponseDTO<List<StockBalanceView>>> balances(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ResponseDTO.success(stockLedgerService.balances(productId, keyword)));
    }

    /** Ledger history. from/to optional (defaults to the last 30 days). */
    @GetMapping("/movements")
    public ResponseEntity<ResponseDTO<List<StockMovementView>>> history(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(stockLedgerService.history(productId, from, to)));
    }

    /** Purchases awaiting check-in — the picker behind STOCK_IN + PURCHASE. */
    @GetMapping("/purchase-candidates")
    public ResponseEntity<ResponseDTO<List<PurchaseCandidateView>>> purchaseCandidates(
            @RequestParam(required = false) Long productId) {
        return ResponseEntity.ok(ResponseDTO.success(stockLedgerService.purchaseCandidates(productId)));
    }

    /** Return claims awaiting check-in — the picker behind RETURN_IN. No product filter (claims are per option). */
    @GetMapping("/return-candidates")
    public ResponseEntity<ResponseDTO<List<ReturnCandidateView>>> returnCandidates() {
        return ResponseEntity.ok(ResponseDTO.success(stockLedgerService.returnCandidates()));
    }
}
