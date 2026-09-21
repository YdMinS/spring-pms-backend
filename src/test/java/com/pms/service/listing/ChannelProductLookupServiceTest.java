package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.response.ChannelProductDetailResponse;
import com.pms.dto.response.ChannelProductSearchResponse;
import com.pms.repository.MarketplaceAccountRepository;
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

/**
 * 마켓 상품 읽기 창구(2609_67 / 01). 계정 해석만 하고 <b>아무 판정도 하지 않는다</b>(PLAN/D4) —
 * 이미 우리 셀로 연결된 상품도 그대로 내려온다. 두 이미지 목록은 절대 합치지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ChannelProductLookupServiceTest {

    @Mock private SellerRepository sellerRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private ListingChannel adapter;
    @InjectMocks private ChannelProductLookupServiceImpl service;

    private static final Long SELLER_ID = 7L;
    private static final Platform PLATFORM = Platform.COUPANG;
    private static final String PRODUCT_ID = "222333444";

    private void givenAccount(boolean active) {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(Seller.builder().id(SELLER_ID).build()));
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(MarketplaceAccount.builder()
                        .id(9L).platform(PLATFORM).isActive(active).build()));
        given(resolver.resolve(PLATFORM)).willReturn(adapter);
    }

    private ImportedProduct.Option option(String itemName, String salePrice) {
        return new ImportedProduct.Option(itemName, "8123", "9123", new BigDecimal(salePrice),
                new BigDecimal(salePrice), 50, Map.of("수량", "6"), Map.of("제품명", "상품 상세페이지 참조"));
    }

    @Test
    void searchReturnsItemsAndNextToken() {
        givenAccount(true);
        given(adapter.searchProducts(eq("생수"), eq(null), any())).willReturn(new ChannelProductPage(List.of(
                new ChannelProductSummary("222333444", "노브랜드 생수 2L 6입", "노브랜드",
                        ListingStatus.SELLING, "2026-08-01T13:20:11"),
                new ChannelProductSummary("222333555", "노브랜드 생수 2L 12입", null,
                        ListingStatus.SUBMITTED, "2026-08-02T09:05:40")), "tok-2"));

        ChannelProductSearchResponse response = service.search(SELLER_ID, "COUPANG", "생수", null);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getPlatformProductId()).isEqualTo("222333444");
        assertThat(response.getItems().get(0).getBrand()).isEqualTo("노브랜드");
        assertThat(response.getItems().get(0).getStatus()).isEqualTo(ListingStatus.SELLING);
        assertThat(response.getItems().get(0).getCreatedAt()).isEqualTo("2026-08-01T13:20:11");
        assertThat(response.getItems().get(1).getBrand()).isNull();
        assertThat(response.getNextToken()).isEqualTo("tok-2");
    }

    /** 🔴 썸네일(마켓 가공본)과 상세(원본에 가까운 사진)를 합치면 사용자가 둘을 구분할 수 없게 된다. */
    @Test
    void detailKeepsThumbnailAndDetailImagesSeparate() {
        givenAccount(true);
        given(adapter.fetchProduct(eq(PRODUCT_ID), any())).willReturn(new ImportedProduct(
                "노브랜드 생수 2L 6입", "노브랜드", "73170", ListingStatus.SELLING, List.of("생수"), "가공식품",
                List.of("https://cdn/rep.jpg"),
                List.of("https://cdn/detail-1.jpg", "https://cdn/detail-2.jpg"),
                List.of(option("6입", "12900"))));

        ChannelProductDetailResponse response = service.detail(SELLER_ID, "COUPANG", PRODUCT_ID);

        assertThat(response.getThumbnailImages()).containsExactly("https://cdn/rep.jpg");
        assertThat(response.getDetailImages())
                .containsExactly("https://cdn/detail-1.jpg", "https://cdn/detail-2.jpg");
        assertThat(response.getBrand()).isEqualTo("노브랜드");
        assertThat(response.getNoticeGroup()).isEqualTo("가공식품");
        assertThat(response.getNotices()).containsExactly(Map.entry("제품명", "상품 상세페이지 참조"));
        assertThat(response.getOptions()).hasSize(1);
        assertThat(response.getOptions().get(0).getAttributes()).containsExactly(Map.entry("수량", "6"));
    }

    /**
     * 🔴 PLAN/D4: 같은 마켓 상품이 이미 우리 셀로 붙어 있어도 200 이다. 미리보기 경로의
     * {@code DetachedCellPolicy} 는 그 경우를 400 으로 막는데, 참고 패널은 <b>바로 그 상품을 보려고</b> 여는
     * 화면이다. 증거는 이 서비스가 {@code ProductListingRepository} 를 <b>주입조차 하지 않는다</b>는 것 —
     * 판정할 수단 자체가 없으므로 이 테스트는 정상 반환만 단언한다(옵션 0개·판매가 0 도 예외가 아니다).
     */
    @Test
    void detailDoesNotRejectAlreadyLinkedProduct() {
        givenAccount(true);
        given(adapter.fetchProduct(eq(PRODUCT_ID), any())).willReturn(new ImportedProduct(
                "이미 파는 상품", null, "73170", ListingStatus.SELLING, List.of(), null,
                List.of(), List.of(), List.of()));

        ChannelProductDetailResponse response = service.detail(SELLER_ID, "COUPANG", PRODUCT_ID);

        assertThat(response.getPlatformProductId()).isEqualTo(PRODUCT_ID);
        assertThat(response.getProductName()).isEqualTo("이미 파는 상품");
        assertThat(response.getOptions()).isEmpty();
        assertThat(response.getNotices()).isEmpty();
    }

    @Test
    void inactiveAccountIsRejected() {
        givenAccount(false);

        assertThatThrownBy(() -> service.detail(SELLER_ID, "COUPANG", PRODUCT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비활성 계정");
    }
}
