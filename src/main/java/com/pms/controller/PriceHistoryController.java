package com.pms.controller;

import com.pms.domain.Platform;
import com.pms.domain.PriceTargetType;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.PriceChangeView;
import com.pms.service.price.PriceHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Price change history API — ADMIN only (FEATURE_2609_28 / PLAN D19·D23).
 *
 * <p>Read-only by design: history is written as a side effect of the change that caused it
 * ({@code PriceHistoryRecorder}). There is no endpoint to add, edit or delete a row — a log that
 * can be edited answers nothing.
 *
 * <p>⚠️ Cost rows carry no channel information; selling rows carry no product cost. Mixing both in
 * one list is intended (that is how "cost rose, price did not" becomes visible), so blank columns
 * are normal.
 *
 * @see PriceHistoryService for what each filter answers
 */
@RestController
@RequestMapping("/api/admin/price-history")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PriceHistoryController {

    private final PriceHistoryService priceHistoryService;

    /** Every parameter is optional; nothing given = the most recent changes. */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<PriceChangeView>>> history(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long optionId,
            @RequestParam(required = false) Long listingId,
            @RequestParam(required = false) Long masterProductId,
            @RequestParam(required = false) Platform platform,
            @RequestParam(required = false) PriceTargetType targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(priceHistoryService.search(
                productId, optionId, listingId, masterProductId, platform, targetType, from, to)));
    }
}
