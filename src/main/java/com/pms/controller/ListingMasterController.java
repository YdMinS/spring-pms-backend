package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ListingMasterCreateRequest;
import com.pms.dto.response.ListingMasterCreateResponse;
import com.pms.dto.response.ListingMasterPreviewResponse;
import com.pms.service.listing.ListingMasterCreateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매상품 → 마스터 프로덕트 생성 엔드포인트(FEATURE_2609_22 / 04). ADMIN 강제는 {@code /api/admin/**} URL
 * 규칙(SecurityConfig)이 한다 — 메서드별 {@code @PreAuthorize} 없음.
 *
 * <p>{@code ListingOptionController} 도 {@code /api/admin/product-listings/**} 를 쓰지만 그쪽은 <b>옵션 축</b>
 * ({@code /options/active|stock|price})이다. 마스터 생성은 축이 달라 컨트롤러를 따로 둔다. 비-admin 인
 * {@code ProductListingController}({@code /api/product-listings})에 넣지 말 것.</p>
 */
@RestController
@RequestMapping("/api/admin/product-listings")
@RequiredArgsConstructor
@Tag(name = "Listing Master", description = "Create a master product from an unlinked channel cell (ADMIN only)")
public class ListingMasterController {

    private final ListingMasterCreateService listingMasterCreateService;

    /** 미리보기 — 요청 바디 없음(셀 id 하나로 충분하다). 저장 0회, 쿠팡 GET 1회. */
    @PostMapping("/{id}/master/preview")
    @Operation(summary = "Preview the master product that would be created from this cell (no writes)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingMasterPreviewResponse>> preview(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingMasterCreateService.preview(id)));
    }

    @PostMapping("/{id}/master")
    @Operation(summary = "Create a master product from this cell and link the cell + its options to it")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingMasterCreateResponse>> create(
            @PathVariable Long id, @Valid @RequestBody ListingMasterCreateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(listingMasterCreateService.create(id, request)));
    }
}
