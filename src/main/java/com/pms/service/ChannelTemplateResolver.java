package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 채널(판매자×플랫폼) 템플릿 해석 seam (FEATURE_2608_06 / 21) — 썸네일·상세 자동생성 공용.
 *
 * <p>채널마다 tenant 기본 템플릿 하나만 강제되던 것을, {@link MarketplaceAccount} 의
 * {@code thumbnailTemplate}/{@code detailTemplate} 지정값으로 우선 해석하고, 미지정(계정 부재 또는
 * FK null)이면 tenant 기본 템플릿({@code findByIsDefaultTrueAndActiveTrue})으로 폴백한다. 즉 계정 키를
 * 아직 안 넣은 상태에서도 템플릿은 항상 리졸브된다(하위호환).</p>
 *
 * <p>해석 순서는 상세와 썸네일이 다르다(2609_20/D2·D3):
 * <ul>
 *   <li>상세 = <b>셀({@code cell.detailTemplate}) → 계정 → 테넌트 기본</b> — 셀 단위 지정이 최우선.</li>
 *   <li>썸네일 = 계정 → 테넌트 기본 (셀 tier 없음 — 2609_20 은 상세만 다룬다).</li>
 * </ul>
 *
 * <p>⚠️ LazyInit 방어(과거 {@code account.getSeller()} 버그 동형): 이 리졸버는 {@code cell.seller}(LAZY)·
 * {@code cell.detailTemplate}(LAZY, 2609_20)·{@code acct.thumbnailTemplate/detailTemplate}(LAZY) 를 접근한다.
 * 호출부(썸네일=
 * {@code ListingAssetServiceImpl.regenerateAssets}@Transactional, 상세=그 안에서 호출되는
 * {@code TemplateDetailContentGenerator.generate})가 트랜잭션 경계 안임을 보장한다. 경계 밖 경로를 추가할 땐
 * finder 에 {@code @EntityGraph} 로 seller/template 를 즉시 초기화할 것.</p>
 *
 * <p>사용 예제:
 * <pre>
 * ThumbnailTemplate t = channelTemplateResolver.resolveThumbnail(cell); // 계정 지정값 ?? tenant 기본
 * DetailTemplate d = channelTemplateResolver.resolveDetail(cell);        // 셀 ?? 계정 ?? tenant 기본
 * </pre>
 *
 * <p>❌ 금지: 생성기/서비스에서 {@code findByIsDefaultTrueAndActiveTrue} 를 직접 조회(전량 이 리졸버 경유).</p>
 */
@Service
@RequiredArgsConstructor
public class ChannelTemplateResolver {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ThumbnailTemplateRepository thumbnailTemplateRepository;
    private final DetailTemplateRepository detailTemplateRepository;

    /** Account's thumbnail template if assigned, else the tenant default (throws if neither exists). */
    public ThumbnailTemplate resolveThumbnail(ProductListing cell) {
        MarketplaceAccount acct = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElse(null);
        // Java has no ?. — explicit null checks (cell.seller/platform are NOT NULL on channel cells).
        if (acct != null && acct.getThumbnailTemplate() != null) {
            return acct.getThumbnailTemplate();
        }
        return thumbnailTemplateRepository.findByIsDefaultTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalStateException("기본 썸네일 템플릿 없음"));
    }

    /** Cell-pinned detail template, else the account's, else the tenant default (throws if none exists). */
    public DetailTemplate resolveDetail(ProductListing cell) {
        // 2609_20/D2: 셀 override 가 최우선. null 이면 아래 기존 2단(계정 ?? 테넌트 기본) 그대로.
        if (cell.getDetailTemplate() != null) {
            return cell.getDetailTemplate();
        }
        MarketplaceAccount acct = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElse(null);
        if (acct != null && acct.getDetailTemplate() != null) {
            return acct.getDetailTemplate();
        }
        return detailTemplateRepository.findByIsDefaultTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalStateException("기본 상세 템플릿 없음"));
    }
}
