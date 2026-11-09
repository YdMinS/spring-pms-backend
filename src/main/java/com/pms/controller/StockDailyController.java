package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.DailyDateMoveRequest;
import com.pms.dto.request.DailyRowEditRequest;
import com.pms.dto.request.PurchasePriceRequest;
import com.pms.dto.request.SellerChangeRequest;
import com.pms.dto.request.StockIntakeRequest;
import com.pms.dto.response.IntakeBarcodeView;
import com.pms.dto.response.ReceiptCreateResult;
import com.pms.dto.response.StockDailyView;
import com.pms.service.PurchaseListService;
import com.pms.service.PurchaseReceiptService;
import com.pms.service.stock.StockDailyService;
import com.pms.service.stock.StockDateEditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 「입고 작업」 and 「그날 입출고 내역」 (FEATURE_2610_19 D117 · D119). ADMIN only, like every {@code /api/admin/**}
 * path. The receipt assignment of the daily view is {@code POST /api/admin/purchase-receipts/assign}, the assignment
 * without a receipt {@code POST /api/admin/purchase-receipts/assign-without-receipt} (FEATURE_2611_01 D2).
 *
 * <p>⚠️ Both multipart parts of {@code /daily/purchase-price} and {@code /daily/row-edit} are {@code required = false}
 * on purpose, as in
 * {@code PurchaseReceiptController}: the photo is optional and a missing JSON part gets the service's 400 message.</p>
 */
@RestController
@RequestMapping("/api/admin/stock")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StockDailyController {

    private final StockDailyService stockDailyService;
    private final PurchaseListService purchaseListService;
    private final PurchaseReceiptService purchaseReceiptService;
    private final StockDateEditService stockDateEditService;

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

    /** 「판매자 바꾸기」 of the daily view (FEATURE_2611_01 D7 · D15). */
    @PostMapping("/daily/change-seller")
    public ResponseEntity<ResponseDTO<Void>> changeSeller(@RequestBody SellerChangeRequest request) {
        purchaseReceiptService.changeSeller(request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    /**
     * 「매입가 넣기」 of the daily view (FEATURE_2611_16 D2 · D7): {@code photo} part (optional, jpeg/png) +
     * {@code request} part (application/json), {@code movedOn} = the day on the screen. receiptId = the receipt the
     * photo went to, null without a photo.
     */
    @PostMapping(value = "/daily/purchase-price", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseDTO<ReceiptCreateResult>> retypePrices(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate movedOn,
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @Valid @RequestPart(value = "request", required = false) PurchasePriceRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseReceiptService.retypePrices(movedOn, request, photo)));
    }

    /**
     * The row popup of the daily view (FEATURE_2611_17 D5): {@code photo} part (optional, jpeg/png) + {@code request}
     * part (application/json), {@code movedOn} = the day on the screen. One save for the seller, the price, the
     * receipt and the day (FEATURE_2611_21 D9) of one row or share. receiptId = the receipt the photo went to or the
     * picked receipt, null otherwise.
     */
    @PostMapping(value = "/daily/row-edit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseDTO<ReceiptCreateResult>> editRow(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate movedOn,
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @Valid @RequestPart(value = "request", required = false) DailyRowEditRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseReceiptService.editDailyRow(movedOn, request, photo)));
    }

    /**
     * The 「날짜 바꾸기」 window of the daily view (FEATURE_2611_21 D9): one row to a new day — an order outbound row with
     * every row of that order on its day (D4). The 400 texts = {@link StockDateEditService#moveRow}.
     */
    @PostMapping("/daily/move-date")
    public ResponseEntity<ResponseDTO<Void>> moveDate(@RequestBody DailyDateMoveRequest request) {
        stockDateEditService.moveRow(request.movementId(), request.newMovedOn());
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
