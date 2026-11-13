package com.pms.service.listing;

import com.pms.domain.Platform;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingMarketValueGroup;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingUpdatePreviewResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-platform channel adapter seam (FEATURE_2608_06 / 3c). The orchestration
 * ({@code ListingRegistrationService}) depends only on this interface — the NAVER adapter is added later (3d).
 *
 * <p><b>⚠️ Async principle (MUST-KEEP)</b>: {@link #register} returns quickly (seconds) with the market
 * product id; it does NOT wait for approval (hours~days). Approval is detected later by {@link #fetchStatus}
 * (manual refresh / sweep). Adapters do HTTP only — the cell state machine lives in the orchestration.</p>
 */
public interface ListingChannel {

    /** Platform key this adapter handles (e.g. {@link Platform#COUPANG}). Resolver matching key. */
    Platform platform();

    /**
     * Validate that the cell may be registered under this channel's own registration policy (FEATURE_2608_06 /
     * 63). A violation throws (e.g. {@code IllegalArgumentException} → 400). The orchestration delegates here
     * before {@link #registerAndReport} — the channel owns the policy (Coupang: required-attribute and notice checks,
     * SINGLE and AB alike — FEATURE_2610_37 / D16; NAVER implements its own). The {@code gen} parameter is kept for
     * contract symmetry with {@link #register}/{@link #update} even when an implementation does not use it.
     *
     * @param cell the DRAFT channel cell
     * @param gen  generated assets (payload source; may be unused by an implementation)
     * @param acct the marketplace account (credentials)
     */
    void validateRegistrable(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct);

    /**
     * Whether the cell's resolved shipping config (channel ?? master ?? account default, 75) satisfies this
     * channel's register requirements (FEATURE_2608_06 / 77). Pure read — never throws; the register path
     * still enforces the same rules and 400s. Exposed as {@code GeneratedProductResponse.shippingReady} so
     * the UI can disable [마켓 등록] before the user hits that 400.
     *
     * <p>The required field set is platform-specific, so each adapter owns the judgement (Coupang delegates
     * to {@link com.pms.service.listing.shipping.ShippingReadiness}; NAVER brings its own in 3d).</p>
     *
     * @param cell the channel cell
     * @return true when the cell may be registered as far as shipping config goes
     */
    boolean isShippingReady(ProductListing cell);

    /**
     * Register the product on the market → returns the market product id (Coupang sellerProductId · 11st prdNo).
     * Does NOT wait for approval.
     *
     * @param cell the DRAFT channel cell
     * @param gen  generated assets (thumbnail URL + detail HTML) — the payload source
     * @param acct the marketplace account (credentials)
     * @return the market product id (Coupang sellerProductId · 11st productNo)
     */
    String register(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct);

    /**
     * Fetch the current market status + per-option ids (1 call). See {@link FetchResult}.
     *
     * @param cell the already-registered cell (has platformProductId)
     * @param acct the marketplace account (credentials)
     * @return status + option ids
     */
    FetchResult fetchStatus(ProductListing cell, MarketplaceAccount acct);

    /**
     * 2609_74/D9: the review reason for the given market status, read from the market on demand.
     *
     * <p>Returns {@code null} when this status carries no reason to look up — the adapter then makes
     * <b>no marketplace call</b>. Which statuses qualify is channel vocabulary, so the adapter owns it.
     * A failed lookup throws; the caller turns that into {@link ReviewNote#failed()}.</p>
     *
     * @param statusName the market's own status text ({@link FetchResult#statusName()})
     */
    default ReviewNote fetchReviewNote(ProductListing cell, MarketplaceAccount acct, String statusName) {
        return null;
    }

    /**
     * Update the listing: rebuild the WHOLE product object → PUT (no incremental option add — approved
     * options cannot be added piecemeal on Coupang; the whole object is re-submitted for re-approval).
     * Coupang instead overwrites the picked items on the product it reads from Coupang right before the PUT
     * (FEATURE_2611_25 / D1).
     *
     * @param cell the registered cell
     * @param gen  generated assets (payload source)
     * @param acct the marketplace account (credentials)
     */
    void update(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct);

    /**
     * {@link #register} plus what happened to 총 수량 (FEATURE_2610_34). The orchestration calls this one; it stores
     * the reported state — the adapter still changes nothing. Default = {@link #register}, nothing to store (only the
     * Coupang adapter overrides it).
     *
     * @return the market product id + the 총 수량 state to store
     */
    default PushOutcome registerAndReport(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        return PushOutcome.unchanged(register(cell, gen, acct));
    }

    /**
     * {@link #update} plus what happened to 총 수량 (FEATURE_2610_34). Default = {@link #update}, nothing to store
     * (only the Coupang adapter overrides it).
     *
     * @return the 총 수량 state to store ({@code platformProductId} is {@code null})
     */
    default PushOutcome updateAndReport(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        update(cell, gen, acct);
        return PushOutcome.unchanged(null);
    }

    /**
     * FEATURE_2611_25 / D1 · D12 · R2: true = a [수정 요청] on this market opens a compare window first
     * ({@link #previewUpdate}) and sends the picked items ({@link #updateAndReport(ProductListing, GeneratedProductData,
     * MarketplaceAccount, ListingUpdateSelection)}). false (default) = the update is sent as it is today.
     */
    default boolean comparesBeforeUpdate() {
        return false;
    }

    /**
     * FEATURE_2611_25 / D3 · D14 · D15: read the product from the market and list the items whose oclyx value differs
     * from it. <b>Read only</b> — nothing is sent and nothing is stored. Only a market whose
     * {@link #comparesBeforeUpdate} is true implements it.
     *
     * @return the differing items, the items whose oclyx value cannot be built and the option lines to pick
     */
    default ListingUpdatePreviewResponse previewUpdate(ProductListing cell, GeneratedProductData gen,
                                                       MarketplaceAccount acct) {
        throw new UnsupportedOperationException(platform() + " 수정 비교 미지원");
    }

    /**
     * FEATURE_2611_25 / D1 · D21: {@link #updateAndReport(ProductListing, GeneratedProductData, MarketplaceAccount)}
     * with what to overwrite. Default = the selection is ignored (a market outside Coupang ignores the body, D21).
     */
    default PushOutcome updateAndReport(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct,
                                        ListingUpdateSelection selection) {
        return updateAndReport(cell, gen, acct);
    }

    /**
     * FEATURE_2611_25 / D23: true = after a passed update the orchestration does NOT turn the deactivated approved
     * options back to not-approved (the market keeps those options as they were, D8). false (default) = as today.
     */
    default boolean keepsInactiveOptionsOnUpdate() {
        return false;
    }

    /**
     * FEATURE_2611_27 / D29 · R2: the groups this market sends from the channel cell's own values. Only a group listed
     * here whose read carries a value turns the cell's 「마켓 값 사용」 flag on, on an import or a market read. Default =
     * none (every group follows the master).
     */
    default Set<ListingMarketValueGroup> marketValueGroups() {
        return Set.of();
    }

    /**
     * Stop selling the listing (approved options cannot be physically deleted → stop-selling).
     *
     * @param cell the registered cell
     * @param acct the marketplace account (credentials)
     */
    void delete(ProductListing cell, MarketplaceAccount acct);

    /**
     * Read a product as it currently is on the market (2609_22 / D8). <b>Read only</b> — no register, update or price
     * call happens on this path.
     *
     * <p>🔴 Required of every market (FEATURE_2611_11 / D17): a market cannot be connected without it — there is no
     * default. [「마켓 기준으로 맞추기」] (D9 · D12) reads the names, the photos and the detail page through it. A field the
     * market does not return is {@code null} (or an empty list), never an exception — the calling service decides what
     * is fatal.</p>
     *
     * <p>⚠️ Importing a market product is a separate rule: callers of the import path still check the supported
     * platforms first ({@code MarketProductAccess.SUPPORTED_PLATFORMS}).</p>
     *
     * @param platformProductId the market product id (Coupang sellerProductId · 11st prdNo · ESM goodsNo)
     * @param account           the marketplace account (credentials)
     * @return the product as it exists on the market
     */
    ImportedProduct fetchProduct(String platformProductId, MarketplaceAccount account);

    /**
     * 상품명으로 마켓 상품을 검색한다(2609_67). 읽기 전용 · 저장 0회.
     *
     * <p>목록 응답에는 <b>사진이 없다</b> — 사진·옵션·속성은 {@link #fetchProduct} 로 한 건씩 읽는다.
     * 기본 구현은 던진다. ⚠️ 호출부는 이 예외에 의존하지 말고 <b>지원 플랫폼 화이트리스트</b>로 먼저 막을 것
     * ({@code GlobalExceptionHandler} 에 {@code UnsupportedOperationException} 핸들러가 없어 500 이 된다).</p>
     *
     * @param name      검색어(호출부가 이미 비어 있지 않음을 보장). 플랫폼 제한은 구현체가 지킨다
     * @param nextToken 이어보기 키 — 첫 페이지는 null
     * @param account   the marketplace account (credentials)
     * @return 후보 목록 + 다음 키(없으면 null). 결과 없음은 빈 목록이지 예외가 아니다
     */
    default ChannelProductPage searchProducts(String name, String nextToken, MarketplaceAccount account) {
        throw new UnsupportedOperationException(platform() + " 상품 검색 미지원");
    }

    /**
     * Change the selling price of ONE option that is already on the market (FEATURE_2609_19 / D4). Unlike
     * {@link #update} (whole object re-submitted for re-approval) this is a partial update that takes effect
     * immediately. A platform that has no such API keeps this default and throws — callers only invoke it for options
     * that carry a {@code platformOptionId} (Coupang vendorItemId · LotteON sitmNo, FEATURE_2611_19 / D3), and never on a
     * platform whose {@link #pushesPriceByUpdate} is true.
     *
     * @param option the channel option to reprice
     * @param price  the price to send, already normalised to whole won by the caller (D13)
     * @param acct   the marketplace account (credentials)
     */
    default void updateOptionPrice(ProductListingOption option, BigDecimal price, MarketplaceAccount acct) {
        throw new UnsupportedOperationException("가격 부분수정 미지원 플랫폼: " + platform());
    }

    /**
     * FEATURE_2611_19 / D18: true = a hand-set selling price reaches this market by re-sending the whole product
     * ({@link #update}, once per save) because the market has one product price plus per-option surcharges (11st ·
     * ESM). false (default) = one {@link #updateOptionPrice} call per option.
     */
    default boolean pushesPriceByUpdate() {
        return false;
    }

    /**
     * FEATURE_2611_19 / D1 · D7: true = a stock saved in oclyx is sent to this market right away
     * ({@link #stockPushTargets} → {@link #updateOptionStocks}). false (default) = this platform has no stock push; the
     * save stays local and the screens keep their [수정 요청] notice.
     */
    default boolean pushesStock() {
        return false;
    }

    /**
     * FEATURE_2611_19 / D5 · D16 · D19 · D20: which of {@code changed} (active options whose stock was just saved) this
     * market can take a stock push for now. The others are saved only. Pure read — no market call.
     *
     * @param cell    the listing
     * @param changed active options of {@code cell}
     * @return a subset of {@code changed}
     */
    default List<ProductListingOption> stockPushTargets(ProductListing cell, List<ProductListingOption> changed) {
        return List.of();
    }

    /**
     * FEATURE_2611_19 / D5 · D16 · D19 · D20: send the market stock of {@code pushes} (all from
     * {@link #stockPushTargets}). A per-option market reports each option on its own; a throw means every option of
     * this call was refused with that message.
     *
     * @param cell   the listing on the market
     * @param pushes option + the market stock to send (channel ?? master ?? 9999)
     * @param acct   the marketplace account (credentials)
     * @return the market's own refusal text by our option id — an id that is absent was accepted
     */
    default Map<Long, String> updateOptionStocks(ProductListing cell, List<StockPush> pushes, MarketplaceAccount acct) {
        throw new UnsupportedOperationException("재고 전송 미지원 플랫폼: " + platform());
    }

    /**
     * Read the prices that are live on the market and pair them with our options (FEATURE_2610_27 / D13 · D16 · D30 ·
     * D37). <b>Read only</b> — nothing is sent and nothing is stored here; {@link MarketPriceApplier} stores the result.
     *
     * <p>{@code fetched} is the result of {@link #fetchStatus} for the same cell — its {@link FetchResult#prices()} are
     * reused so the status call is not repeated (Coupang: no extra call · 11st: + 1 call · ESM: + 1 call). A failed
     * read throws; the caller keeps the previous values (D24 · D28).</p>
     *
     * @param cell    the cell on the market (has platformProductId)
     * @param acct    the marketplace account (credentials)
     * @param fetched this cell's {@link #fetchStatus} result
     * @return the prices per our option id
     */
    default MarketPrices readMarketPrices(ProductListing cell, MarketplaceAccount acct, FetchResult fetched) {
        throw new UnsupportedOperationException("마켓 가격 읽기 미지원 플랫폼: " + platform());
    }
}
