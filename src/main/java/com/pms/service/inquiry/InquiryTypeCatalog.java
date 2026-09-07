package com.pms.service.inquiry;

import com.pms.domain.InquiryType;
import com.pms.domain.Platform;
import com.pms.dto.response.InquiryTypeCatalogResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "어느 플랫폼이 어떤 문의 유형을 제공하는가" 의 <b>단일 원천</b> (FEATURE_2609_23 / D4).
 *
 * 🔴 이 표를 프론트·모바일에 복제하지 말 것. 네이버는 유형 구성이 다르고, 클라이언트가
 * {@code if (platform === 'COUPANG')} 로 탭을 그리기 시작하면 플랫폼을 붙일 때 화면을 다시 짠다.
 * 유형이 늘거나 라벨이 바뀌면 <b>여기만</b> 고친다.
 *
 * <p>파일: {@code service/inquiry/InquiryTypeCatalog.java} — 노출 경로는 {@code GET /api/inquiries/types}.
 */
@Component
public class InquiryTypeCatalog {

    private static final Map<Platform, List<InquiryTypeCatalogResponse.Option>> BY_PLATFORM = Map.of(
            Platform.COUPANG, List.of(
                    new InquiryTypeCatalogResponse.Option(InquiryType.PRODUCT_QNA, "상품문의"),
                    new InquiryTypeCatalogResponse.Option(InquiryType.CALL_CENTER, "고객센터문의")));

    /**
     * 주어진 플랫폼들의 지원 유형 목록. 아는 플랫폼만 담는다(모르는 플랫폼은 조용히 빠진다) —
     * 빈 유형 목록을 내려보내면 화면에 유형 없는 탭 줄이 생긴다.
     */
    public List<InquiryTypeCatalogResponse> forPlatforms(List<Platform> platforms) {
        return platforms.stream()
                .distinct()
                .map(platform -> Optional.ofNullable(BY_PLATFORM.get(platform))
                        .map(types -> new InquiryTypeCatalogResponse(platform.name(), types))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
