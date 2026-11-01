package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.StockIntakeRequest;
import com.pms.dto.response.IntakeBarcodeView;
import com.pms.dto.response.StockDailyView;
import com.pms.service.PurchaseListService;
import com.pms.service.stock.StockDailyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 「입고 작업」 and 「그날 입출고 내역」 (FEATURE_2610_19 D117 · D119). ADMIN only, like every {@code /api/admin/**}
 * path. The receipt assignment of the daily view is {@code POST /api/admin/purchase-receipts/assign}.
 */
@RestController
@RequestMapping("/api/admin/stock")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StockDailyController {

    private final StockDailyService stockDailyService;
    private final PurchaseListService purchaseListService;

    /** One day of the ledger (D119 · D123) and the undecided products of that day (D147). */
    @GetMapping("/daily")
    public ResponseEntity<ResponseDTO<StockDailyView>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ResponseDTO.success(stockDailyService.daily(date)));
    }

    /** 「입고 작업」 scan: the product owning the barcode, or {@code found=false}. */
    @GetMapping("/intake/barcode")
    public ResponseEntity<ResponseDTO<IntakeBarcodeView>> barcode(@RequestParam String value) {
        return ResponseEntity.ok(ResponseDTO.success(stockDailyService.barcode(value)));
    }

    /** 「입고 작업」 [입고 저장] (D117 ⑥). 400 while the stock link is off. */
    @PostMapping("/intake")
    public ResponseEntity<ResponseDTO<Void>> intake(@Valid @RequestBody StockIntakeRequest request) {
        purchaseListService.addIntake(request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
