package com.pms.service.listing;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;

/**
 * 같은 마켓 상품의 <b>기존 셀을 재사용해도 되는가</b>(FEATURE_2609_66 / D1). 편입(
 * {@link CoupangListingImportServiceImpl})과 마스터 생성({@link MasterFromChannelServiceImpl})이 공유하는
 * 유일한 판정이다 — 두 경로가 갈라지면 "편입은 되는데 생성은 안 되는" 상태가 다시 생긴다(2609_63 의 실제 사고).
 *
 * <p>🔴 메시지 3종을 바꾸지 말 것 — 프론트가 substring 으로 판정해 조치 안내를 덧붙인다
 * ({@code ImportCoupangProductModal.tsx:45}).</p>
 */
public final class DetachedCellPolicy {

    private DetachedCellPolicy() {
    }

    /**
     * @param existing {@code findByPlatformProductId} 결과(없으면 null = 신규, 항상 통과)
     * @throws IllegalArgumentException 아직 마스터에 붙어 있거나 · 플랫폼이 다르거나 · 남의 판매자 셀일 때
     */
    public static void requireReusable(ProductListing existing, Platform platform, Long sellerId) {
        if (existing == null) {
            return;
        }
        // 아직 주인이 있다 → 그 마스터에서 [마스터 연결 해제] 를 먼저 해야 한다(화면 라벨 그대로).
        if (existing.getMasterProduct() != null) {
            throw new IllegalArgumentException("이미 다른 상품에 연결된 쿠팡 상품입니다");
        }
        // ⚠️ getMasterProduct()·getSeller().getId() 는 FK id 만 읽는 것이라 LAZY 프록시를 깨우지 않는다.
        if (existing.getPlatform() != platform) {
            throw new IllegalArgumentException("다른 플랫폼의 판매상품입니다");
        }
        if (!existing.getSeller().getId().equals(sellerId)) {
            throw new IllegalArgumentException("다른 판매자의 판매상품입니다");
        }
    }
}
