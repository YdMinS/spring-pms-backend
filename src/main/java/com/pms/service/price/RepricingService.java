package com.pms.service.price;

import com.pms.domain.Platform;
import com.pms.dto.response.RepricingCandidatesResponse;

/**
 * 마진 경보 조회(FEATURE_2609_39 / PLAN D2).
 *
 * <p>🔴 <b>읽기 전용이다.</b> 판매가를 다시 계산하지도, 마켓에 보내지도 않는다 — 비용이 바뀌었다고 값을 자동으로
 * 덮으면 "일부러 마진을 높여 둔 상품"의 의도가 매번 지워진다(D1). 실행은 사람이 별도 액션으로 한다.</p>
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

    /** 목록에 담을 행의 범위. 집계({@code groups})는 이 값과 무관하게 항상 전체를 센다. */
    enum Scope {
        /** 기본값. 대응 필요(below)한 행만 — 비용이 내려가 마진이 좋아진 건은 여기 뜨지 않는다(D10). */
        BELOW,
        /** 대상 전부. 「이런 변화가 아니어도 골라서 바꾸고 싶다」를 이 필터 하나로 흡수한다(D10·D12). */
        ALL
    }
}
