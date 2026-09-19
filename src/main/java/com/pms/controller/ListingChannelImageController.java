package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.ChannelImageResponse;
import com.pms.service.listing.ListingChannelImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이미 연결된 채널 셀의 마켓 이미지 조회(온보딩, 2026-09-19). ADMIN 강제는 {@code /api/admin/**} URL 규칙
 * (SecurityConfig)이 한다 — 메서드별 {@code @PreAuthorize} 없음. 셀의 테넌트 스코프는 서비스가 지킨다.
 *
 * <p>축이 <b>"마켓에서 읽어오는 값"</b>이라 컨트롤러를 따로 둔다. {@code ListingAssetController} 는 같은
 * {@code /api/admin/product-listings} 를 쓰지만 그쪽은 <b>우리가 생성한 자산</b>(썸네일·상세) 축이다 —
 * 마켓 원본 읽기를 그 안에 섞으면 두 개념이 한 컨트롤러에서 뒤섞인다.</p>
 */
@RestController
@RequestMapping("/api/admin/product-listings")
@RequiredArgsConstructor
@Tag(name = "Listing Channel Image",
        description = "Read the marketplace image URLs of an already-linked channel cell (ADMIN only)")
public class ListingChannelImageController {

    private final ListingChannelImageService listingChannelImageService;

    /**
     * 조회 전용 — 저장 0회, 마켓 GET 1회. 이미지는 <b>URL 로만</b> 나간다(내려받지 않는다).
     * 썸네일(마켓 가공본)과 상세는 분리된 목록으로, 원본 순서 그대로 돌려준다.
     */
    @GetMapping("/{id}/channel-images")
    @Operation(summary = "Get the marketplace thumbnail/detail image URLs of this cell (no writes)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ChannelImageResponse>> images(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingChannelImageService.images(id)));
    }
}
