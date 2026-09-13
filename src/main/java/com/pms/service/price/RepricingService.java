package com.pms.service.price;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.request.PriceOverrideRequest;
import com.pms.dto.response.PriceOverrideResult;
import com.pms.dto.response.RecalculateResult;
import com.pms.dto.response.RepricePushResult;
import com.pms.dto.response.RepricingCandidatesResponse;
import com.pms.service.listing.ListingChannel;

import java.math.BigDecimal;
import java.util.List;

/**
 * 마진 경보 조회(FEATURE_2609_39 / PLAN D2)와 그 실행(D7).
 *
 * <p>🔴 <b>조회는 읽기 전용이다.</b> 판매가를 다시 계산하지도, 마켓에 보내지도 않는다 — 비용이 바뀌었다고 값을
 * 자동으로 덮으면 "일부러 마진을 높여 둔 상품"의 의도가 매번 지워진다(D1). 실행은 사람이 별도 액션으로 한다.</p>
 *
 * <p>🔴 실행은 <b>2단계이고 끝까지 분리된다</b>(D7): {@link #recalculate}(로컬 판매가만) →
 * {@link #push}(그 값을 마켓으로). 한 메서드로 합치지 않는다 — 쿠팡 가격변경은 승인 없이 즉시 실판매가라,
 * 매입 단가 오타 하나가 한 번의 클릭으로 실제 판매가가 되면 되돌릴 수 없다.</p>
 *
 * <p>공식은 {@link com.pms.service.PriceCalculator} 가 소유한다. 이 서비스는 같은 해석기를 <b>셀 단위로 캐시</b>해
 * 옵션 수에 비례한 쿼리가 나가지 않게 하는 것이 절반의 일이다(D16).</p>
 */
public interface RepricingService {

    /**
     * 대응 필요 목록 + 판매자 × 채널 집계.
     *
     * @param sellerId 판매자 필터. null = 전 판매자
     * @param platform 채널 필터. null = 전 채널(지원 = 쿠팡뿐, D17)
     * @param scope    행 필터. null = {@link Scope#BELOW}
     */
    RepricingCandidatesResponse candidates(Long sellerId, Platform platform, Scope scope);

    /**
     * ① 판매가 재계산 — <b>로컬 전용</b>(D7 ①). 마켓 호출 0회.
     *
     * <p>🔴 셀의 <b>AUTO 옵션 전부</b>가 다시 계산된다(D21 — 화면이 고른 옵션만이 아니다). 재계산 이음매
     * ({@code ListingAssetService.recalculateOptionPrices})의 단위가 셀이기 때문이고, 옵션만 골라 계산하는
     * 이음매를 새로 만들면 공식 소유자가 둘이 된다.</p>
     *
     * <p>🔴 {@code needsMarketSync} 를 켜지 않는다(D9). 그 깃발은 콘텐츠 재심사 대기라는 뜻이라,
     * 가격 때문에 켜면 {@code dashboard/listings/sync} 콘솔에 잘못 쌓인다.</p>
     *
     * @param listingIds 재계산할 셀. 상한은 요청 DTO 가 소유한다
     * @return 셀 단위 부분 실패를 담은 결과(PARTIAL 존재)
     * @throws com.pms.exception.ResourceNotFoundException 이 테넌트의 셀이 아닌 id 가 하나라도 있으면 404
     */
    RecalculateResult recalculate(List<Long> listingIds);

    /**
     * ② 마켓 반영 — 재계산된 판매가를 마켓으로 전송하고 {@code market_price} 에 기록한다(D7 ②).
     *
     * <p>🔴 <b>{@code ListingOptionService.setOptionPrices} 를 쓰지 않는다</b>(D8). 그 경로는 값을 주면 옵션을
     * {@code MANUAL_OVERRIDE} 로 표시해(2609_19/D3) 그 옵션을 다음 재계산부터 영구 제외시킨다 — 이 기능이
     * 스스로를 무력화한다. 어댑터 {@link ListingChannel#updateOptionPrice} 를 직접 부르고
     * {@code priceSource} 는 건드리지 않는다.</p>
     *
     * @param optionIds 전송할 옵션. 상한은 요청 DTO 가 소유한다
     * @return 옵션 단위 결과({@code pushed}/{@code skipped}/{@code failed}), 429 중단이면 {@code stopped}
     * @throws com.pms.exception.ResourceNotFoundException 이 테넌트의 셀에 속하지 않는 옵션 id 가 하나라도
     *                                                     있으면 404 (D24 — 부분 성공으로 섞지 않는다)
     */
    RepricePushResult push(List<Long> optionIds);

    /**
     * 판매가 직접 입력 — 사람이 친 값을 <b>로컬 판매가에만</b> 적는다(FEATURE_2609_42 / D1). 마켓 호출 0회.
     *
     * <p>🔴 {@code priceSource} 를 <b>건드리지 않는다</b>(2609_42 D2). {@code ListingOptionService.setOptionPrices}
     * 를 쓰면 옵션이 {@code MANUAL_OVERRIDE} 가 되어(2609_19 D3) 다음 재계산부터 영구 제외되는데, 그러면 이
     * 기능이 하려는 일의 정반대가 된다 — 여기 입력은 「이번 한 번만」이고 다음 재계산이 공식값으로 덮는 것이
     * 정상이다.</p>
     *
     * <p>🔴 {@code marketPrice} 도 건드리지 않는다(D10) — 그래야 그 행이 「아직 안 밀림」으로 떠서 사람이
     * {@link #push} 를 눌러야 한다는 사실을 화면이 말할 수 있다.</p>
     *
     * <p>⚠️ 이미 직접 지정가({@code MANUAL_OVERRIDE})인 옵션은 이 경로의 대상이 아니다(D4) — 그 가격은 이미
     * 사람이 소유하고 있고, 바꾸려면 옵션 편집 화면(2609_19)이 맞다. 서버는 {@code skipped} 로 돌려준다.</p>
     *
     * @param items 옵션 id + 새 판매가. 상한은 요청 DTO 가 소유한다
     * @return 옵션 단위 결과({@code applied}/{@code skipped}/{@code failed}). 마켓을 부르지 않으므로 중단은 없다
     * @throws com.pms.exception.ResourceNotFoundException 이 테넌트의 셀에 속하지 않는 옵션 id 가 하나라도
     *                                                     있으면 404 (D24 — 부분 성공으로 섞지 않는다)
     */
    PriceOverrideResult override(List<PriceOverrideRequest.Item> items);

    /**
     * 셀 1건 재계산 — <b>독립 트랜잭션</b>({@code REQUIRES_NEW}). 인터페이스에 있는 이유는 하나다:
     * {@link #recalculate} 가 주입된 <b>프록시</b>로 불러야 트랜잭션 경계가 실제로 열리기 때문이다
     * (자기 호출은 {@code REQUIRES_NEW} 를 무효로 만든다).
     *
     * @return 판매가가 실제로 달라진 옵션 수
     */
    int recalculateOne(ProductListing cell);

    /**
     * 옵션 1건의 「전송 + 저장」 — <b>독립 트랜잭션</b>({@code REQUIRES_NEW}). {@link #recalculateOne} 과 같은
     * 이유로 인터페이스에 있다.
     *
     * <p>🔴 하나로 묶어 {@code saveAll} 하면 20번째에서 터졌을 때 <b>이미 마켓에 나간 19건의 기록이 사라진다</b>
     * (마켓은 롤백되지 않는다). 전송이 성공한 그 순간의 값만 이 트랜잭션이 커밋한다.</p>
     */
    void pushOne(ListingChannel channel, ProductListingOption option, MarketplaceAccount account);

    /**
     * 옵션 1건의 「직접 입력 저장 + 이력」 — <b>독립 트랜잭션</b>({@code REQUIRES_NEW}). {@link #pushOne} 과 같은
     * 이유로 인터페이스에 있다(프록시로 불러야 경계가 열린다).
     *
     * <p>🔴 하나로 묶으면 20번째 실패가 <b>이미 저장된 19건을 되돌린다</b>. {@code push} 와 같은 계약이다.</p>
     */
    void overrideOne(ProductListingOption option, BigDecimal price);

    /** 목록에 담을 행의 범위. 집계({@code groups})는 이 값과 무관하게 항상 전체를 센다. */
    enum Scope {
        /** 기본값. 대응 필요(below)한 행만 — 비용이 내려가 마진이 좋아진 건은 여기 뜨지 않는다(D10). */
        BELOW,
        /** 대상 전부. 「이런 변화가 아니어도 골라서 바꾸고 싶다」를 이 필터 하나로 흡수한다(D10·D12). */
        ALL
    }
}
