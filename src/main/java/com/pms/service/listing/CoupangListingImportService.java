package com.pms.service.listing;

import com.pms.dto.request.ListingImportPreviewRequest;
import com.pms.dto.request.ListingImportRequest;
import com.pms.dto.response.ChannelAddResponse;
import com.pms.dto.response.ListingImportPreviewResponse;

/**
 * Brings a product already on the market into an existing master as a <b>channel cell</b> (FEATURE_2609_22 / D8).
 *
 * <p>Two steps: {@link #preview} reads the market once and returns only the mapping verdict and the input rows — it
 * <b>saves nothing</b>. {@link #importListing} builds the master options, the cell, its options and the cell BOM from
 * the composition the user filled in, and <b>after that commit</b> puts the market's 「등록상품명」 · photos · detail on
 * the cell (FEATURE_2611_11 / D2 · D4 · D6 · D8) and generates the assets — neither failure undoes the cell.</p>
 *
 * <p><b>Required rules</b></p>
 * <ul>
 *   <li>⚠️ This feature makes <b>read calls only</b> to the market. Never add a register (POST) · update (PUT) · price
 *       change here — an import mirrors what is already on the market; it does not change the market.</li>
 *   <li>⚠️ Prices · stock · option ids are <b>never taken from the request</b>. The commit reads the market once more and
 *       the server decides (changes between preview and commit are taken; client values are not trusted).</li>
 *   <li>⚠️ Supported platforms are a <b>whitelist</b> ({@code MarketProductAccess.SUPPORTED_PLATFORMS}). Every
 *       market can read a product (FEATURE_2611_11 / D17), but importing one is still Coupang-only.</li>
 * </ul>
 *
 * @see ChannelAddService channel add (master options copied → DRAFT cell) — the sibling path of this service
 */
public interface CoupangListingImportService {

    /**
     * 마켓 상품을 조회해 매핑 판정 + 구성 입력 줄을 돌려준다. <b>쓰기 0회</b>, 마켓 GET 1회.
     *
     * @param masterProductId 편입 대상 마스터
     * @param request         (판매자, 플랫폼, 마켓 상품 id)
     * @return 미리보기 결과
     */
    ListingImportPreviewResponse preview(Long masterProductId, ListingImportPreviewRequest request);

    /**
     * 사용자가 채운 구성으로 채널 셀을 만든다. 셀·옵션·BOM 은 전부 성공하거나 전부 롤백된다(단일 트랜잭션).
     *
     * <p>🔴 자동생성(썸네일·상세·판매가)은 그 트랜잭션이 <b>커밋된 뒤</b> 돌고, 실패해도 셀을 되돌리지 않는다
     * (「새 마스터」 도 3단 페이지 저장 뒤 이 메서드로 붙는다 — UX D70). 사진이 하나도 없는 물품이면 자동생성이 400 을
     * 던지는데, 그때 셀까지 사라지면 "쿠팡 ID 를 넣었는데 아무것도 안 생긴다" 가 되기 때문이다 — 사진은 나중에
     * 채우고 [재생성] 하면 된다. 성공 여부는 응답 {@code assetsGenerated} 로 드러난다.</p>
     *
     * @param masterProductId 편입 대상 마스터
     * @param request         구성 수량 + 마스터 옵션명
     * @return 새 셀 id + 마켓 상태 + 생성된 자산 + 자동생성 성공 여부 (+ 카테고리 경고)
     */
    ChannelAddResponse importListing(Long masterProductId, ListingImportRequest request);
}
