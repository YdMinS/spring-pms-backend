package com.pms.controller;

import com.pms.domain.Platform;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CategoryAttributesRequest;
import com.pms.dto.request.ImportProductImagesRequest;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterFromChannelPreviewRequest;
import com.pms.dto.request.MasterFromChannelRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductQuery;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.request.MasterSourceImageRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.request.MasterZoneImagesRequest;
import com.pms.dto.request.ShippingForceApplyRequest;
import com.pms.dto.request.ShippingOverrideRequest;
import com.pms.dto.request.TagsRequest;
import com.pms.dto.response.ApplyOptionNamesResponse;
import com.pms.dto.response.CategoryMetaResponse;
import com.pms.dto.response.ChannelSyncPreviewResponse;
import com.pms.dto.response.ListingMasterCreateResponse;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterCategoryResponse;
import com.pms.dto.response.MasterFromChannelPreviewResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductByComponentsResponse;
import com.pms.dto.response.MasterProductImageResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.dto.response.ShippingForceApplyResponse;
import com.pms.service.CategoryMetaService;
import com.pms.service.MasterProductImageService;
import com.pms.service.MasterProductService;
import com.pms.service.listing.MasterFromChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Master product definition (CRUD + options) + channel coverage matrix (FEATURE_2608_06 / 3a, 3b-1).
 *
 * <p>All endpoints are ADMIN-only via the global {@code /api/admin/**} rule (SecurityConfig) — no
 * per-method {@code @PreAuthorize}. Tenant scoping is enforced in the service (tenant-filtered reads).</p>
 */
@RestController
@RequestMapping("/api/admin/master-products")
@RequiredArgsConstructor
@Tag(name = "Master Product", description = "Master product + coverage matrix API (ADMIN only)")
public class MasterProductController {

    private final MasterProductService masterProductService;
    private final MasterProductImageService masterProductImageService;
    private final CategoryMetaService categoryMetaService;
    private final MasterFromChannelService masterFromChannelService;

    /**
     * Paged master list (110). {@code @ParameterObject} makes springdoc expand {@link MasterProductQuery}
     * into individual query parameters in swagger-ui (without it the object renders as a body schema).
     */
    @GetMapping
    @Operation(summary = "List master products",
            description = "활성 마스터 목록(페이징). page(0-indexed, 기본 0) / size(기본 25, 최대 100으로 clamp) / "
                    + "sort(`필드,방향` — 허용 필드: createdAt, 기본 `createdAt,desc`, 허용 외 필드는 400) / "
                    + "search(마스터 이름 부분일치·대소문자 무시). 응답 data 는 Page 객체 "
                    + "(content/totalElements/totalPages/number/size).")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Page<MasterProductResponse>>> getMasterProducts(
            @ParameterObject @ModelAttribute MasterProductQuery query) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMasterProducts(query)));
    }

    /**
     * Duplicate check for the create screen (2609_46). Declared before {@code /{id}} for readability —
     * the literal path always beats the template in Spring's pattern comparator either way.
     */
    @GetMapping("/by-components")
    @Operation(summary = "Find master products built from the exact same component set",
            description = "구성상품(물품) 조합이 **정확히 같은** 마스터를 돌려준다. 순서 무관, 부분집합·상위집합은 "
                    + "다른 마스터로 보고 제외한다. 삭제된 마스터도 `active:false` 로 함께 내려간다 "
                    + "(목록에 안 보여서 또 만드는 것을 막기 위함). 없으면 빈 배열.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterProductByComponentsResponse>>> findByComponents(
            @RequestParam("productIds") List<Long> productIds) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.findByComponents(productIds)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get master product by ID")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> getMasterProduct(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMasterProduct(id)));
    }

    @GetMapping("/{id}/matrix")
    @Operation(summary = "Get channel coverage matrix for a master product")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingMatrixResponse>> getMatrix(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMatrix(id)));
    }

    @GetMapping("/{id}/channel-sync-preview")
    @Operation(summary = "채널에 반영할 항목 미리보기(읽기 전용)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ChannelSyncPreviewResponse>> previewChannelSync(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.previewChannelSync(id)));
    }

    @PostMapping
    @Operation(summary = "Create master product (options included = atomic create)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> createMasterProduct(
            @Valid @RequestBody MasterProductRequest request) {
        MasterProductResponse response = masterProductService.createMasterProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    // ---------------------------------------------------------------- master from a marketplace product (2609_45)

    @PostMapping("/from-channel/preview")
    @Operation(summary = "Preview a marketplace product before creating a master product from it",
            description = "마켓 상품 id 하나로 옵션·카테고리·속성을 조회한다. 읽기 전용 — 저장 0회.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterFromChannelPreviewResponse>> previewMasterFromChannel(
            @Valid @RequestBody MasterFromChannelPreviewRequest request) {
        // 200, not 201 — nothing is created here.
        return ResponseEntity.ok(ResponseDTO.success(masterFromChannelService.preview(request)));
    }

    @PostMapping("/from-channel")
    @Operation(summary = "Create a master product (+ options + channel cell) from a marketplace product",
            description = "쿠팡에 없는 정보(마스터 이름·구성상품·옵션별 수량·표준 카테고리)만 받고, 가격·재고·"
                    + "옵션 식별자·상태·태그는 커밋 시 마켓을 다시 조회해 서버가 확정한다.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingMasterCreateResponse>> createMasterFromChannel(
            @Valid @RequestBody MasterFromChannelRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterFromChannelService.create(request)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update master product content (name/fieldValues/active/components)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> updateMasterProduct(
            @PathVariable Long id, @Valid @RequestBody MasterProductUpdateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.updateMasterProduct(id, request)));
    }

    @PatchMapping("/{id}/tags")
    @Operation(summary = "Replace the master product's tag pool (33; deduped, empty clears)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> updateTags(
            @PathVariable Long id, @Valid @RequestBody TagsRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.updateTags(id, request.getTags())));
    }

    @PutMapping("/{id}/registration-name-suffix")
    @Operation(summary = "Set the master-level 옵션확인 suffix override (69; replace, null = inherit)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> updateRegistrationNameSuffix(
            @PathVariable Long id, @Valid @RequestBody OptionCheckSuffixRequest request) {
        masterProductService.updateRegistrationNameSuffix(id, request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PatchMapping("/{id}/shipping-override")
    @Operation(summary = "Replace the master-level shipping overrides (75; place keys dropped, empty clears)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> updateShippingOverride(
            @PathVariable Long id, @RequestBody ShippingOverrideRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                masterProductService.updateShippingOverride(id, request.getOverride())));
    }

    @PostMapping("/{id}/shipping-override/apply-to-channels")
    @Operation(summary = "Overwrite the selected channels' shipping settings with the master's "
            + "(77/79; body optional, no listingIds = every channel; place keys kept)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ShippingForceApplyResponse>> applyShippingOverrideToChannels(
            @PathVariable Long id, @RequestBody(required = false) ShippingForceApplyRequest request) {
        int affected = masterProductService.applyShippingOverrideToChannels(
                id, request == null ? null : request.getListingIds());
        return ResponseEntity.ok(ResponseDTO.success(
                ShippingForceApplyResponse.builder().affectedChannels(affected).build()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete master product (active=false)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteMasterProduct(@PathVariable Long id) {
        masterProductService.deleteMasterProduct(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PostMapping(value = "/{id}/image", consumes = "multipart/form-data")
    @Operation(summary = "Upload a base-image override (sets sourceImageUrl)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> uploadImage(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.uploadMasterImage(id, file)));
    }

    @PostMapping("/{id}/options")
    @Operation(summary = "Create master option (validates component coverage)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterOptionResponse>> createOption(
            @PathVariable Long id, @Valid @RequestBody MasterOptionRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.createOption(id, request)));
    }

    @PatchMapping("/{id}/options/{optionId}")
    @Operation(summary = "Update master option (items replace whole set + re-validate)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterOptionResponse>> updateOption(
            @PathVariable Long id, @PathVariable Long optionId, @Valid @RequestBody MasterOptionRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.updateOption(id, optionId, request)));
    }

    @PostMapping("/{id}/options/apply-names")
    @Operation(summary = "Reset every channel option of this master to the master option's name "
            + "(2609_22/D4; channel-only options are skipped)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ApplyOptionNamesResponse>> applyMasterOptionNames(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.applyMasterOptionNames(id)));
    }

    @DeleteMapping("/{id}/options/{optionId}")
    @Operation(summary = "Delete master option")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteOption(
            @PathVariable Long id, @PathVariable Long optionId) {
        masterProductService.deleteOption(id, optionId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    // ---------------------------------------------------------------- standard category (single, 44)

    @PutMapping("/{id}/category")
    @Operation(summary = "Set the master's single standard category")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterCategoryResponse>> setCategory(
            @PathVariable Long id, @Valid @RequestBody MasterCategoryRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.setCategory(id, request)));
    }

    @GetMapping("/{id}/category")
    @Operation(summary = "Get the master's standard category (null fields if unset)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterCategoryResponse>> getCategory(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getCategory(id)));
    }

    @DeleteMapping("/{id}/category")
    @Operation(summary = "Clear the master's standard category")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> clearCategory(@PathVariable Long id) {
        masterProductService.clearCategory(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- category meta (47)

    @GetMapping("/{id}/category-meta")
    @Operation(summary = "Get the (platform × category) required-attribute/notice schema + master values")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<CategoryMetaResponse>> getCategoryMeta(
            @PathVariable Long id, @RequestParam String platform) {
        return ResponseEntity.ok(ResponseDTO.success(categoryMetaService.getMeta(id, Platform.from(platform))));
    }

    @PatchMapping("/{id}/category-attributes")
    @Operation(summary = "Store master-level category attribute + notice values (no regeneration)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> updateCategoryAttributes(
            @PathVariable Long id, @RequestBody CategoryAttributesRequest request) {
        categoryMetaService.updateCategoryAttributes(id, request.getAttributes(), request.getNotices(),
                request.getNoticeGroup());
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    // ---------------------------------------------------------------- image pool + field mapping (37)

    @PostMapping(value = "/{id}/images", consumes = "multipart/form-data")
    @Operation(summary = "Upload an image into the master's pool")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductImageResponse>> uploadToPool(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        MasterProductImageResponse response = masterProductImageService.uploadToPool(id, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @PostMapping("/{id}/images/import")
    @Operation(summary = "Import product image slots into the pool as reference entries (live-links)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterProductImageResponse>>> importProductImages(
            @PathVariable Long id, @Valid @RequestBody ImportProductImagesRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                masterProductImageService.importProductImages(id, request.getProductImageIds())));
    }

    @GetMapping("/{id}/images")
    @Operation(summary = "List the master's pool images (with mapping state)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterProductImageResponse>>> listPool(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductImageService.listPool(id)));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @Operation(summary = "Remove a pool image (and its mappings)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> removeFromPool(@PathVariable Long id, @PathVariable Long imageId) {
        masterProductImageService.removeFromPool(id, imageId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/zones/{zoneId}/images")
    @Operation(summary = "Set a detail zone's mapped images (ordered; empty clears)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterProductImageResponse>>> setZoneImages(
            @PathVariable Long id, @PathVariable String zoneId,
            @Valid @RequestBody MasterZoneImagesRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                masterProductImageService.setZoneImages(id, zoneId, request.getImageIds())));
    }

    @PutMapping("/{id}/source-image")
    @Operation(summary = "Set (imageId) or clear (null) the master's cover photo")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductImageResponse>> setSourceImage(
            @PathVariable Long id, @RequestBody MasterSourceImageRequest request) {
        MasterProductImageResponse response = masterProductImageService.setSourceImage(id, request.getImageId());
        // imageId != null → 200 with the set cover image; imageId == null (cleared) → 204.
        return response == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(ResponseDTO.success(response));
    }
}
