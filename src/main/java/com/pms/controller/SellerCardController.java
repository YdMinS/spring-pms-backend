package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.SellerCardCreateRequest;
import com.pms.dto.request.SellerCardUpdateRequest;
import com.pms.dto.response.SellerCardView;
import com.pms.service.SellerCardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Seller card API — ADMIN only (FEATURE_2610_18 D52 · D91 · D92). Read by the receipt popup (web · mobile) and the
 * web seller screen.
 *
 * @see SellerCardService
 */
@RestController
@RequestMapping("/api/admin/seller-cards")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SellerCardController {

    private final SellerCardService sellerCardService;

    /** Visible cards of every seller, seller id then card id. */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<SellerCardView>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(sellerCardService.getVisible()));
    }

    /** Register a card. A full card number → 400 (D92). */
    @PostMapping
    public ResponseEntity<ResponseDTO<SellerCardView>> create(@Valid @RequestBody SellerCardCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(sellerCardService.create(request)));
    }

    /** Edit alias and masked number (D91). */
    @PutMapping("/{cardId}")
    public ResponseEntity<ResponseDTO<SellerCardView>> update(
            @PathVariable Long cardId,
            @Valid @RequestBody SellerCardUpdateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(sellerCardService.update(cardId, request)));
    }

    /** [삭제] = hide (D91). */
    @DeleteMapping("/{cardId}")
    public ResponseEntity<ResponseDTO<Void>> hide(@PathVariable Long cardId) {
        sellerCardService.hide(cardId);
        return ResponseEntity.ok(ResponseDTO.success((Void) null));
    }
}
