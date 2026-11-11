package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.StockFillRequest;
import com.pms.dto.response.FillCandidateView;
import com.pms.dto.response.FillLotView;
import com.pms.service.stock.StockFillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Filling an order outbound row that left without a lot (FEATURE_2611_22 D3). ADMIN only, like every
 * {@code /api/admin/**} path. The 400 texts = {@link StockFillService}.
 */
@RestController
@RequestMapping("/api/admin/stock/fill")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StockFillController {

    private final StockFillService stockFillService;

    /** The 「채울까요?」 window after an intake save (D3 ①): {@code lotIds} = the save's lot ids, comma separated. */
    @GetMapping("/candidates")
    public ResponseEntity<ResponseDTO<List<FillCandidateView>>> candidates(@RequestParam List<Long> lotIds) {
        return ResponseEntity.ok(ResponseDTO.success(stockFillService.candidates(lotIds)));
    }

    /** The lots 「일별 입출고 내역」 [입고로 채우기] offers for one row (D3 ② · D15). */
    @GetMapping("/lots")
    public ResponseEntity<ResponseDTO<List<FillLotView>>> lots(@RequestParam Long movementId) {
        return ResponseEntity.ok(ResponseDTO.success(stockFillService.lots(movementId)));
    }

    /** [채우기] of either window (D3). */
    @PostMapping
    public ResponseEntity<ResponseDTO<Void>> fill(@RequestBody StockFillRequest request) {
        stockFillService.fill(request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
