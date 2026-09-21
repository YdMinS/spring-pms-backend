package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.ChannelProductDetailResponse;
import com.pms.dto.response.ChannelProductSearchResponse;
import com.pms.service.listing.ChannelProductLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마켓 상품 읽기 창구(2609_67 — 물품 등록 참고 패널). ADMIN 강제는 {@code /api/admin/**} URL 규칙
 * (SecurityConfig)이 한다 — 메서드별 {@code @PreAuthorize} 없음({@link ListingChannelImageController} 와 동일).
 *
 * <p>🔴 <b>읽기 전용이고 아무 판정도 하지 않는다</b>(PLAN/D4): 이미 우리 셀로 연결된 상품도 그대로 200 이다.
 * 마스터를 만들 수 있는지 판정하는 미리보기({@code /master-products/from-channel/preview})와 섞지 말 것 —
 * 그쪽은 연결된 상품을 400 으로 막는다.</p>
 *
 * <p>고른 사진을 물품에 붙이는 경로는 여기가 아니라 {@code ProductImageController} 의
 * {@code POST /images/from-url} 이다(사진은 물품이 만들어진 뒤에 붙는다).</p>
 */
@RestController
@RequestMapping("/api/admin/channel-products")
@RequiredArgsConstructor
@Tag(name = "Channel Product Lookup",
        description = "Read marketplace products by name/id for the product-register reference panel (ADMIN only)")
public class ChannelProductLookupController {

    private final ChannelProductLookupService channelProductLookupService;

    /**
     * 상품명 검색. 🔴 결과에 <b>사진이 없다</b>(쿠팡 목록 응답 스펙) — 사진은 단건 조회로만 온다.
     * 검색어는 20자까지다(쿠팡 제한). {@code nextToken} 이 null 로 돌아오면 마지막 페이지다.
     *
     * <p>⚠️ 검색어 제약에 {@code @Validated} + {@code @Size} 를 쓰지 않는다 — 요청 파라미터 위반은
     * {@code ConstraintViolationException} 인데 {@code GlobalExceptionHandler} 에 그 핸들러가 없어 500 이 된다.
     * 빈 값은 서비스가, 20자 초과는 어댑터가 {@code IllegalArgumentException}(→400) 으로 막는다.</p>
     */
    @GetMapping
    @Operation(summary = "Search this seller's marketplace products by name (read-only, no photos)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ChannelProductSearchResponse>> search(
            @RequestParam Long sellerId,
            @RequestParam String platform,
            @RequestParam String name,
            @RequestParam(required = false) String nextToken) {
        return ResponseEntity.ok(ResponseDTO.success(
                channelProductLookupService.search(sellerId, platform, name, nextToken)));
    }

    /** 단건 조회 — 사진·옵션·속성·고시. ⚠️ 쿠팡 GET 이 1~2회 나간다(속성 단위 해석 때 메타를 한 번 더 읽는다). */
    @GetMapping("/{platformProductId}")
    @Operation(summary = "Read one marketplace product verbatim (photos, options, attributes, notices)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ChannelProductDetailResponse>> detail(
            @PathVariable String platformProductId,
            @RequestParam Long sellerId,
            @RequestParam String platform) {
        return ResponseEntity.ok(ResponseDTO.success(
                channelProductLookupService.detail(sellerId, platform, platformProductId)));
    }
}
