package com.pms.service;

import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterCompositionRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductQuery;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.response.ApplyOptionNamesResponse;
import com.pms.dto.response.ChannelSyncPreviewResponse;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterCategoryResponse;
import com.pms.dto.response.MasterChannelOptionsResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Master product definition + reads + the channel coverage matrix (FEATURE_2608_06 / 3a, 3b-1).
 *
 * <p>Reads are tenant-scoped via {@code @TenantId} (see {@code MasterProductRepository}); cross-tenant
 * ids resolve to 404 through the tenant-filtered {@code findScopedById}. 3b-1 adds master CRUD (soft
 * delete) plus option CRUD with component-coverage validation.</p>
 */
public interface MasterProductService {

    /**
     * Paged list of masters (110). 🔁 2609_72: the {@code active} filter is gone — <b>every</b> master is
     * returned, including rows that older code had hidden with {@code active=false}. Removing a master is a
     * real delete now ({@code MasterDeleteService}), so there is nothing left to hide.
     *
     * <p>The raw {@link MasterProductQuery} is normalised here — the front omits default-valued keys,
     * so missing values arrive as {@code 0}/{@code null}:</p>
     * <ul>
     *   <li>{@code page < 0} → 0; {@code size <= 0} → 25; {@code size > 100} → 100 (clamped, never 400)</li>
     *   <li>{@code sort} null/blank → {@code createdAt,desc}. Format {@code field,direction}; direction
     *       optional (defaults to desc), case-insensitive. The field must be in the sort whitelist —
     *       anything else throws {@link IllegalArgumentException} → HTTP 400. {@code id} is always
     *       appended as a tie-breaker so page boundaries are deterministic.</li>
     *   <li>{@code search} null/blank → no condition; otherwise one trimmed keyword matched against
     *       three things at once (FEATURE_2609_60): a case-insensitive partial match on the master
     *       <b>name</b>, an <b>exact</b> match on a listing's 상품 ID
     *       ({@code ProductListing.platformProductId}), or an <b>exact</b> match on an option's 옵션 ID
     *       ({@code ProductListingOption.platformOptionId}). The caller never says which kind of id it
     *       typed, and the response never says which one matched.</li>
     * </ul>
     *
     * <p>{@code registrationName} stays null on this path (N+1 guard) — see {@link #getMasterProduct}.</p>
     *
     * @param query raw request conditions (never null; bound via {@code @ModelAttribute})
     * @return one page of responses with the source-zone cover overlaid
     * @throws IllegalArgumentException if the sort field is not whitelisted
     */
    Page<MasterProductResponse> getMasterProducts(MasterProductQuery query);

    MasterProductResponse getMasterProduct(Long id);

    /**
     * Whether the master is a mixed-composition (AB) product (FEATURE_2608_06 / 63). Determined by the master's
     * component count: {@code >= 2} components → AB (bundle), {@code 1} → SINGLE, {@code masterId == null} →
     * SINGLE (backfill transition window). Platform-neutral domain fact (component count) — channel adapters
     * reuse it (Coupang skips attributes for AB; NAVER may reuse the judgment too).
     *
     * @param masterId the master product id (nullable → false)
     * @return true if AB (2+ components), false otherwise
     */
    boolean isBundle(Long masterId);

    ListingMatrixResponse getMatrix(Long id);

    /**
     * Every channel cell of the master with its full option set, in one call (FEATURE_2609_61 / D6).
     *
     * <p>The per-cell endpoint ({@code GET /api/admin/product-listings/{id}/options}) would cost one HTTP call
     * per cell for the same screen; this one batches both sides (cells: 1 query, their options: 1 query) so the
     * query count does not grow with the cell count.</p>
     *
     * <p>Options are returned untouched — every option of every cell, in repository order, inactive and
     * channel-only ones included. Filtering/sorting is the caller's job.</p>
     *
     * @param masterId master product id (tenant-scoped; 404 if absent)
     * @return the cells with their options; {@code cells} empty when the master has no listing
     */
    MasterChannelOptionsResponse getChannelOptions(Long masterId);

    /**
     * What [채널에 반영하기] would change, without changing anything (FEATURE_2608_06 / 89).
     *
     * <p>Mirrors the propagation rules exactly: only differences a propagation run removes are counted
     * ({@code inSync}/{@code totals}). Market-registered orphans are reported but not counted, and cells
     * propagation skips (no generated assets) are left out of the response entirely.</p>
     *
     * @param masterId master product id (tenant-scoped; 404 if absent)
     * @return the difference list, {@code inSync=true} + empty channels when nothing is propagatable
     */
    ChannelSyncPreviewResponse previewChannelSync(Long masterId);

    /**
     * Masters already built from the <b>exact same</b> component (product) set (2609_46).
     *
     * <p>A master's identity is its component set — quantity differences (1개 / 5개 묶음) are options of the
     * same master, never a second master. Matching is set equality, order-independent and duplicate-tolerant:
     * a subset or a superset is a <i>different</i> master and is not returned.</p>
     *
     * <p>🔁 2609_72: the response no longer carries {@code active} — masters are never hidden, so every
     * match is a master the caller can actually see on the list screen.</p>
     *
     * @param productIds component product ids (null/empty → empty result; deduped before matching)
     * @return matching masters ordered by id, empty when the combination is free
     */
    List<com.pms.dto.response.MasterProductByComponentsResponse> findByComponents(List<Long> productIds);

    /**
     * Create a master + its options atomically.
     *
     * <p>⚠️ Rejects (400) a component set that an existing master already uses — see
     * {@link #findByComponents}. The create screen checks the same rule up front; this is the
     * server-side backstop. The update path is deliberately <b>not</b> guarded.</p>
     */
    MasterProductResponse createMasterProduct(MasterProductRequest request);

    MasterProductResponse updateMasterProduct(Long id, MasterProductUpdateRequest request);

    /**
     * 구성상품 + 옵션 전체를 한 트랜잭션에서 교체한다 (2609_64).
     *
     * <p>구성과 옵션 수량 벡터는 서로를 검증하므로(집합 동등, {@code assertCoversComponents}) 따로 저장하면
     * 어느 쪽도 못 바꾼다. 이 메서드만이 둘을 동시에 받는다.</p>
     *
     * <p>⚠️ 자산 재생성({@code MasterPropagationService.propagate})은 여기서 부르지 않는다 — 이 메서드가 방금
     * 쓴 셀 행을 셀별 {@code REQUIRES_NEW} 트랜잭션이 다시 쓰다 락을 기다리고, 커밋 전 구성을 못 봐서 옛
     * 구성으로 상세·판매가를 되돌려 놓는다. 호출은 커밋 뒤 컨트롤러가 한다(2609_64/D12).</p>
     */
    MasterProductResponse updateComposition(Long id, MasterCompositionRequest request);

    /**
     * Replace the master's tag pool (33). The list is order-preserving deduped; an empty list clears it.
     *
     * @param id   master product id (tenant-scoped; 404 if absent)
     * @param tags the new tag pool (deduped on save)
     * @return the updated master product (with {@code tags} exposed)
     */
    MasterProductResponse updateTags(Long id, java.util.List<String> tags);

    /**
     * Replace the master-level "옵션확인" suffix override (69). Replace semantics: both fields stored as sent,
     * null = inherit (blank suffix normalizes to null). 404 if the master is absent.
     */
    MasterProductResponse updateRegistrationNameSuffix(Long id, OptionCheckSuffixRequest req);

    /**
     * Replace the master-level shipping overrides (75). Key whitelist only (place keys silently dropped —
     * they are channel-level); null/empty map clears the override. 404 if the master is absent.
     */
    MasterProductResponse updateShippingOverride(Long id, java.util.Map<String, String> override);

    /**
     * Force the master's shipping settings onto the selected channels (FEATURE_2608_06 / 77, semantics revised
     * in 79): each selected cell's own shipping override is <b>overwritten</b> with the master's — the master's
     * master-level keys are written onto the cell, and master-level keys the master leaves empty are removed
     * from the cell. After this a cell's shipping settings are exactly the master's.
     *
     * <p>⚠️ This <b>writes values into the channel</b>, it does not merely clear it: the cell now owns those
     * values, so a later master edit no longer reaches it (the {@code channel ?? master ?? account} priority is
     * unchanged — the channel simply wins with the copied values). Place keys (outbound / return center) are
     * account-specific and are <b>kept</b> untouched. Idempotent: a cell already equal to the master is not
     * saved and not counted.</p>
     *
     * @param id         master product id (404 when absent / cross-tenant)
     * @param listingIds channels to apply to; {@code null}/empty = every linked channel. An id outside this
     *                   master's channels is rejected with 400.
     * @return how many channel cells actually changed
     */
    int applyShippingOverrideToChannels(Long id, java.util.List<Long> listingIds);

    MasterOptionResponse createOption(Long masterId, MasterOptionRequest request);

    MasterOptionResponse updateOption(Long masterId, Long optionId, MasterOptionRequest request);

    void deleteOption(Long masterId, Long optionId);

    /**
     * [옵션명 일괄 적용] (FEATURE_2609_22 / D4): walk every cell of this master and reset each option that is
     * linked to a master option back to that option's name, with {@code optionNameSource = AUTO}.
     *
     * <p>Channel-only options (D2) are skipped — there is no master name to take. A cell where the reset
     * would leave two options with the same name is skipped <b>as a whole</b> and reported in
     * {@code warnings}: the other cells still get applied (never fail the batch over one cell).</p>
     *
     * <p>⚠️ Local only — nothing is pushed to the market (the new names travel with the next [수정 요청]).</p>
     */
    ApplyOptionNamesResponse applyMasterOptionNames(Long masterId);

    /** Set the master's single standard category (FEATURE_2608_06 / 44). 404 if master/category absent. */
    MasterCategoryResponse setCategory(Long masterId, MasterCategoryRequest request);

    /** Get the master's standard category (both fields null if unset). */
    MasterCategoryResponse getCategory(Long masterId);

    /** Clear the master's standard category (idempotent; sets it to null). */
    void clearCategory(Long masterId);

    /**
     * Upload a base-image override (FEATURE_2608_06 / 3b-2): validates + stores the file and sets
     * {@code MasterProduct.sourceImageUrl}. Asset regeneration is a separate call (listing regenerate).
     *
     * @param id   master product id (tenant-scoped; 404 if absent)
     * @param file image file (validated by {@code ImageValidator})
     * @return the updated master product
     */
    MasterProductResponse uploadMasterImage(Long id, MultipartFile file);
}
