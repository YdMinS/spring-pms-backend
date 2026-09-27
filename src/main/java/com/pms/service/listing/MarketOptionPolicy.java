package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;

/**
 * "이 채널 옵션이 쿠팡에 있는가"를 묻는 판정을 한 곳에 모은다 (FEATURE_2609_74).
 *
 * <p>잠금(삭제 가드)·이름 내려보내기·「변경 미반영」 표시·이름 변경 잠금이 각자 같은 질문을 하면 규칙이 갈라진다 —
 * 실제로 잠금만 옵션명으로 비교해 쿠팡에서 가져온 옵션의 잠금이 빠졌다. 판정은 전부 여기서만 한다.</p>
 *
 * <p>❌ 옵션명으로 판정하지 말 것. 쿠팡 옵션명은 형식이 섞여 있고 쿠팡이 바꿀 수 있다.</p>
 */
public final class MarketOptionPolicy {

    /** 2609_74/D32·D33: 400 message when a direct channel rename hits a name-locked option. One text for every status. */
    public static final String NAME_LOCKED_MESSAGE =
            "쿠팡 심사 중인 옵션은 이름을 바꿀 수 없습니다. [승인 새로고침]으로 결과를 받은 뒤 바꾸세요";

    private MarketOptionPolicy() {
    }

    /** 옵션이 쿠팡에 실려 있거나 다음 전송에 실린다: 켜져 있음 · 옵션 ID 있음 · 승인된 적 있음 중 하나. */
    public static boolean onMarket(ProductListingOption option) {
        return Boolean.TRUE.equals(option.getActive()) || option.isMarketRegistered();
    }

    /** 쿠팡에 올린 판매상품의 옵션이고 {@link #onMarket} 이다 — 삭제 잠금과 「변경 미반영」의 조건. */
    public static boolean carriedOnMarket(ProductListing cell, ProductListingOption option) {
        return cell.getPlatformProductId() != null && onMarket(option);
    }

    /**
     * 쿠팡에 올린 판매상품의 옵션인데 옵션 ID 를 아직 못 받았다. 이 상태의 옵션은 승인 결과를 받을 때
     * <b>이름으로만</b> 짝을 찾으므로({@code ListingRegistrationServiceImpl.fetchStatus}) 이름을 바꾸면 짝이 끊긴다.
     */
    public static boolean awaitingMarketId(ProductListing cell, ProductListingOption option) {
        return cell.getPlatformProductId() != null && option.getPlatformOptionId() == null;
    }

    /**
     * 2609_74/D32·D33: a person may not rename this channel option — it is {@link #awaitingMarketId} and the
     * cell is not REJECTED (SUBMITTED · SELLING incl. partial approval · SUSPENDED all lock). A REJECTED cell
     * unlocks: the name is fixed and sent again with [수정 요청]. The status is the last [승인 새로고침] result —
     * Coupang is never asked separately. Used by the three human rename paths only; the automatic master
     * rename propagation keeps {@link #awaitingMarketId} (D25 — status-independent).
     */
    public static boolean nameLocked(ProductListing cell, ProductListingOption option) {
        return awaitingMarketId(cell, option) && cell.getStatus() != ListingStatus.REJECTED;
    }
}
