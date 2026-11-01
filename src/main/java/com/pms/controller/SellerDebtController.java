package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.DebtUnitPriceRequest;
import com.pms.dto.request.SettlementCreateRequest;
import com.pms.dto.response.SellerDebtEntryView;
import com.pms.dto.response.SellerDebtPairView;
import com.pms.service.SellerDebtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 「정산관리 > 사업자 간 채무」 (FEATURE_2610_19 D51 · D90 · D141 · D143). ADMIN only. */
@RestController
@RequestMapping("/api/admin/seller-debts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SellerDebtController {

    private final SellerDebtService sellerDebtService;

    /** Seller pairs with a balance or a 「미기입」 debt. */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<SellerDebtPairView>>> pairs() {
        return ResponseEntity.ok(ResponseDTO.success(sellerDebtService.pairs()));
    }

    /** The cause list of one pair. */
    @GetMapping("/entries")
    public ResponseEntity<ResponseDTO<List<SellerDebtEntryView>>> entries(
            @RequestParam Long sellerA, @RequestParam Long sellerB) {
        return ResponseEntity.ok(ResponseDTO.success(sellerDebtService.entries(sellerA, sellerB)));
    }

    /** 「돈으로 정리」 (D90). */
    @PostMapping("/settlements")
    public ResponseEntity<ResponseDTO<Void>> settle(@Valid @RequestBody SettlementCreateRequest request) {
        sellerDebtService.settle(request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    /** [취소] of a settlement (D90). */
    @PostMapping("/settlements/{settlementId}/cancel")
    public ResponseEntity<ResponseDTO<Void>> cancel(@PathVariable Long settlementId) {
        sellerDebtService.cancelSettlement(settlementId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    /** [단가 입력] · [단가 수정] (D141 · D143). */
    @PutMapping("/{debtId}/unit-price")
    public ResponseEntity<ResponseDTO<Void>> unitPrice(@PathVariable Long debtId,
                                                       @Valid @RequestBody DebtUnitPriceRequest request) {
        sellerDebtService.fillUnitPrice(debtId, request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
