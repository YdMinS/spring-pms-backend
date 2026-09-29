package com.pms.service.listing;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.dto.request.MasterFromChannelPreviewRequest;
import com.pms.dto.response.MasterFromChannelPreviewResponse;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 「마켓 상품으로 시작」 미리보기(FEATURE_2609_45 / 01 → 2609_79). 미리보기는 <b>저장 0회</b>로 옵션·카테고리·
 * 속성만 만든다. 🔁 2609_79 / UX D70: 저장(마스터 + 셀 한 번에 만들기)은 없어졌다 — 그 테스트도 함께 지웠다.
 */
@ExtendWith(MockitoExtension.class)
class MasterFromChannelServiceTest {

    @Mock private SellerRepository sellerRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private ListingChannel channel;
    @InjectMocks private MasterFromChannelServiceImpl service;

    private static final Long SELLER_ID = 7L;
    private static final Long CATEGORY_ID = 3L;
    private static final Platform PLATFORM = Platform.COUPANG;
    private static final String PRODUCT_ID = "222333444";
    private static final String COUPANG_CATEGORY = "73170";

    // ---- fixtures ----

    private ImportedProduct.Option marketOption(String name, String vendorItemId, String salePrice,
                                                Map<String, String> attributes) {
        return new ImportedProduct.Option(name, vendorItemId, "9" + vendorItemId,
                new BigDecimal(salePrice), new BigDecimal(salePrice).add(BigDecimal.valueOf(3000)), 85,
                attributes, Map.of("제품명", "상품 상세페이지 참조"));
    }

    private ImportedProduct marketProduct(ImportedProduct.Option... options) {
        // 온보딩(2026-09-19): 마켓 사진 2종(가공된 썸네일 / 상세 원본)은 미리보기가 그대로 내보낸다.
        return new ImportedProduct("노브랜드 생수 2L 6입", COUPANG_CATEGORY, ListingStatus.SELLING,
                List.of("생수", "2L"), "가공식품",
                List.of("https://cdn/rep.jpg"), List.of("https://cdn/detail.jpg"), List.of(options));
    }

    /** 옵션 2개: 공통 속성(개당 중량) + 옵션마다 다른 속성(수량). */
    private ImportedProduct twoOptionProduct() {
        return marketProduct(
                marketOption("6입", "8123", "12900", Map.of("수량", "6", "개당 중량", "36.9")),
                marketOption("12입", "8124", "23900", Map.of("수량", "12", "개당 중량", "36.9")));
    }

    private MasterFromChannelPreviewRequest previewRequest() {
        return MasterFromChannelPreviewRequest.builder()
                .sellerId(SELLER_ID).platform(PLATFORM.name()).platformProductId(PRODUCT_ID).build();
    }

    private ProductListing existingCell(Long id, MasterProduct linkedMaster, Long sellerId) {
        return ProductListing.builder()
                .id(id).platform(PLATFORM).platformProductId(PRODUCT_ID)
                .name("예전 이름").status(ListingStatus.SELLING)
                .seller(Seller.builder().id(sellerId).build())
                .masterProduct(linkedMaster)
                .build();
    }

    // ---- stub helpers (kept granular: a guard test must not stub what it never reaches) ----

    private void givenAccount() {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(MarketplaceAccount.builder()
                        .id(9L).platform(PLATFORM).isActive(true).build()));
        given(resolver.resolve(PLATFORM)).willReturn(channel);
    }

    private void givenMarket(ImportedProduct product) {
        given(productListingRepository.findByPlatformProductId(PRODUCT_ID)).willReturn(Optional.empty());
        given(channel.fetchProduct(eq(PRODUCT_ID), any())).willReturn(product);
    }

    /** D2 역조회 성공. */
    private void givenCategoryResolved() {
        PlatformCategory platformCategory = PlatformCategory.builder()
                .id(50L).platform(PLATFORM).code(COUPANG_CATEGORY).name("생수").build();
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.of(platformCategory));
        given(categoryMappingRepository.findByPlatformCategoryId(50L))
                .willReturn(Optional.of(CategoryMapping.builder()
                        .id(60L).platform(PLATFORM)
                        .category(Category.builder().id(CATEGORY_ID).name("생수").build())
                        .platformCategory(platformCategory).build()));
    }

    // ---- preview ----

    @Test
    void preview_returnsOptionsAndCategory() {
        givenAccount();
        givenMarket(twoOptionProduct());
        givenCategoryResolved();

        MasterFromChannelPreviewResponse response = service.preview(previewRequest());

        assertThat(response.getProductName()).isEqualTo("노브랜드 생수 2L 6입");
        assertThat(response.getSuggestedMasterName()).isEqualTo("노브랜드 생수 2L 6입");
        assertThat(response.getStatus()).isEqualTo(ListingStatus.SELLING);
        assertThat(response.isCategoryResolved()).isTrue();
        assertThat(response.getSuggestedCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(response.getSuggestedCategoryName()).isEqualTo("생수");
        assertThat(response.getOptions()).extracting(MasterFromChannelPreviewResponse.Option::getItemName)
                .containsExactly("6입", "12입");
        assertThat(response.getOptions().get(0).getPlatformOptionId()).isEqualTo("8123");
        assertThat(response.getOptions().get(0).getStockQuantity()).isEqualTo(85);
        // D4-1: 공통은 마스터 몫, 상이는 그 옵션 몫.
        assertThat(response.getCommonAttributes()).containsExactly(Map.entry("개당 중량", "36.9"));
        assertThat(response.getOptions().get(0).getAttributes()).containsExactly(Map.entry("수량", "6"));
        assertThat(response.getOptions().get(1).getAttributes()).containsExactly(Map.entry("수량", "12"));
        assertThat(response.getNotices()).containsEntry("제품명", "상품 상세페이지 참조");
        assertThat(response.getNoticeGroup()).isEqualTo("가공식품");
        // 온보딩(2026-09-19): 사진 URL 은 두 종류를 구분해 그대로 노출한다(적재는 소비자 몫).
        assertThat(response.getThumbnailImages()).containsExactly("https://cdn/rep.jpg");
        assertThat(response.getDetailImages()).containsExactly("https://cdn/detail.jpg");
        // 🔴 미리보기는 아무것도 쓰지 않는다.
        verify(productListingRepository, never()).save(any());
    }

    @Test
    void preview_categoryNotMapped_returnsNullCategory() {
        givenAccount();
        givenMarket(twoOptionProduct());
        given(platformCategoryRepository.findByPlatformAndCode(PLATFORM, COUPANG_CATEGORY))
                .willReturn(Optional.empty());

        MasterFromChannelPreviewResponse response = service.preview(previewRequest());

        // 예외가 아니다 — 프론트가 사용자에게 표준 카테고리를 고르게 한다(D2).
        assertThat(response.isCategoryResolved()).isFalse();
        assertThat(response.getSuggestedCategoryId()).isNull();
        assertThat(response.getSuggestedCategoryName()).isNull();
        assertThat(response.getCategoryCode()).isEqualTo(COUPANG_CATEGORY);
    }

    @Test
    void preview_duplicateItemName_throws400() {
        givenAccount();
        givenMarket(marketProduct(
                marketOption("6입", "8123", "12900", Map.of()),
                marketOption("6입", "8124", "23900", Map.of())));

        assertThatThrownBy(() -> service.preview(previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("옵션명이 중복됩니다")
                .hasMessageContaining("6입");
    }

    @Test
    void preview_zeroPrice_throws400() {
        givenAccount();
        givenMarket(marketProduct(marketOption("6입", "8123", "0", Map.of())));

        assertThatThrownBy(() -> service.preview(previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("판매가 없는 옵션: 6입");
    }

    @Test
    void preview_alreadyLinkedProductId_throws400() {
        givenAccount();
        given(productListingRepository.findByPlatformProductId(PRODUCT_ID))
                .willReturn(Optional.of(existingCell(41L, MasterProduct.builder().id(99L).build(), SELLER_ID)));

        assertThatThrownBy(() -> service.preview(previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 다른 상품에 연결된 쿠팡 상품입니다");
        verify(channel, never()).fetchProduct(any(), any());
    }

    /** 2609_66/D6: 떼어낸 셀이 있으면 미리보기가 재사용을 알린다. */
    @Test
    void preview_detachedListing_flagsReuse() {
        givenAccount();
        given(productListingRepository.findByPlatformProductId(PRODUCT_ID))
                .willReturn(Optional.of(existingCell(41L, null, SELLER_ID)));
        given(channel.fetchProduct(eq(PRODUCT_ID), any())).willReturn(twoOptionProduct());

        MasterFromChannelPreviewResponse response = service.preview(previewRequest());

        assertThat(response.isReusesExistingListing()).isTrue();
        verify(productListingRepository, never()).save(any());
    }

    /** 남의 판매자 셀은 재사용 대상이 아니다 — 마켓 조회 <b>전에</b> 막힌다. */
    @Test
    void preview_listingOfAnotherSeller_throws400() {
        givenAccount();
        given(productListingRepository.findByPlatformProductId(PRODUCT_ID))
                .willReturn(Optional.of(existingCell(41L, null, 999L)));

        assertThatThrownBy(() -> service.preview(previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("다른 판매자의 판매상품입니다");
        verify(channel, never()).fetchProduct(any(), any());
    }

    /** UX D47: 계정 판정은 공용 규칙({@link MarketProductAccess}) — 비활성 계정은 마켓 조회 전에 400. */
    @Test
    void preview_inactiveAccount_throws400() {
        given(resolver.resolve(PLATFORM)).willReturn(channel);
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(MarketplaceAccount.builder()
                        .id(9L).platform(PLATFORM).isActive(false).build()));

        assertThatThrownBy(() -> service.preview(previewRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비활성 계정");
        verify(channel, never()).fetchProduct(any(), any());
    }
}
