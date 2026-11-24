package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ClaimWorkRequest;
import com.pms.dto.request.OutboundWorkRequest;
import com.pms.dto.response.ClaimWorkView;
import com.pms.dto.response.ShippedOrderView;
import com.pms.service.stock.ClaimWorkService;
import com.pms.service.stock.OutboundWorkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「출고 작업」 · 「반품 재입고」 · 「교환 발송」 (FEATURE_2611_34 D35). ADMIN only, like every {@code /api/admin/**}
 * path.
 */
@RestController
@RequestMapping("/api/admin/stock")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StockWorkController {

    private final OutboundWorkService outboundWorkService;
    private final ClaimWorkService claimWorkService;

    /** 「출고 작업」 [폐기 저장] · [매입처 반품 저장] (D35 ⑤). */
    @PostMapping("/outbound-work")
    public ResponseEntity<ResponseDTO<Void>> outboundWork(@Valid @RequestBody OutboundWorkRequest request) {
        outboundWorkService.save(request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    /** 「반품 재입고」: claims by pickup invoice (D35 ①). */
    @GetMapping("/return-work/by-return-invoice")
    public ResponseEntity<ResponseDTO<List<ClaimWorkView>>> byReturnInvoice(@RequestParam String value) {
        return ResponseEntity.ok(ResponseDTO.success(claimWorkService.byReturnInvoice(value)));
    }

    /** 「반품 재입고」 [송장 없이 반품 완료]: claims by the box's outbound invoice (D35 ②). */
    @GetMapping("/return-work/by-parcel-invoice")
    public ResponseEntity<ResponseDTO<List<ClaimWorkView>>> byParcelInvoice(@RequestParam String value) {
        return ResponseEntity.ok(ResponseDTO.success(claimWorkService.byParcelInvoice(value)));
    }

    /** 「반품 재입고」 [송장 없이 반품 완료]: orders shipped in the last 14 days (D35 ③ · D39). */
    @GetMapping("/return-work/shipped-orders")
    public ResponseEntity<ResponseDTO<List<ShippedOrderView>>> shippedOrders() {
        return ResponseEntity.ok(ResponseDTO.success(claimWorkService.shippedOrders()));
    }

    /** 「반품 재입고」: claims of the picked order (D35 ③). */
    @GetMapping("/return-work/orders/{orderId}")
    public ResponseEntity<ResponseDTO<List<ClaimWorkView>>> byOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(ResponseDTO.success(claimWorkService.byOrder(orderId)));
    }

    /** 「반품 재입고」 [반품 재입고 저장] (D35 ④). */
    @PostMapping("/return-work")
    public ResponseEntity<ResponseDTO<Void>> returnWork(@Valid @RequestBody ClaimWorkRequest request) {
        claimWorkService.returnIn(request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    /** 「교환 발송」: exchange claims by reship invoice (D35 ⑥). */
    @GetMapping("/exchange-work/by-reship-invoice")
    public ResponseEntity<ResponseDTO<List<ClaimWorkView>>> byReshipInvoice(@RequestParam String value) {
        return ResponseEntity.ok(ResponseDTO.success(claimWorkService.byReshipInvoice(value)));
    }

    /** 「교환 발송」 「보낼 교환」 list (D35 ⑥ · D42). */
    @GetMapping("/exchange-work/open")
    public ResponseEntity<ResponseDTO<List<ClaimWorkView>>> openExchanges() {
        return ResponseEntity.ok(ResponseDTO.success(claimWorkService.openExchanges()));
    }

    /** 「교환 발송」 [교환 발송 저장] (D35 ⑦). */
    @PostMapping("/exchange-work")
    public ResponseEntity<ResponseDTO<Void>> exchangeWork(@Valid @RequestBody ClaimWorkRequest request) {
        claimWorkService.exchangeShip(request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
