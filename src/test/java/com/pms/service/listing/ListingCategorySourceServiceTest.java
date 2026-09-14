package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingCategorySourceResponse;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.MasterChannelConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [마스터 카테고리로 변경] 토글 (FEATURE_2609_45 / D13): 켜면 채널 카테고리 코드·고시 품목군·셀 옵션 메타가
 * 모두 비워지고, 끄기는 400 이다(복원할 원본이 없다). 쿠팡에는 아무것도 보내지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ListingCategorySourceServiceTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @InjectMocks private ListingCategorySourceServiceImpl service;

    private static final Long LISTING_ID = 50L;

    private ProductListing cell(String categoryCode, String noticeGroup) {
        return ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG)
                .masterProduct(MasterProduct.builder().id(1L).build())
                .platformCategoryCode(categoryCode).categoryNoticeGroup(noticeGroup).build();
    }

    private void givenSaveEchoes() {
        given(productListingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        lenient().when(masterChannelConfigService.resolveChannelCategory(any()))
                .thenReturn(new MasterChannelConfigService.ChannelCategory(
                        PlatformCategory.builder().platform(Platform.COUPANG).code("58630").build(), false));
    }

    @Test
    void updateCategorySource_true_clearsCodeGroupAndOptionMeta() {
        given(productListingRepository.findScopedById(LISTING_ID))
                .willReturn(Optional.of(cell("73170", "가공식품")));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A")
                        .categoryAttributes(Map.of("수량", "6"))
                        .categoryNotices(Map.of("품목 또는 명칭", "쌀")).build()));
        givenSaveEchoes();

        ListingCategorySourceResponse response = service.updateCategorySource(LISTING_ID, true);

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getPlatformCategoryCode()).isNull();
        assertThat(cellCaptor.getValue().getCategoryNoticeGroup()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductListingOption>> optionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(productListingOptionRepository).saveAll(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue()).singleElement()
                .satisfies(option -> {
                    assertThat(option.getCategoryAttributes()).isNull();
                    assertThat(option.getCategoryNotices()).isNull();
                });

        assertThat(response.isUseMasterCategory()).isTrue();
        assertThat(response.getPreviousCategoryCode()).isEqualTo("73170");
        assertThat(response.getEffectiveCategoryCode()).isEqualTo("58630");
    }

    @Test
    void updateCategorySource_false_throws400() {
        given(productListingRepository.findScopedById(LISTING_ID))
                .willReturn(Optional.of(cell("73170", "가공식품")));

        assertThatThrownBy(() -> service.updateCategorySource(LISTING_ID, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("채널 카테고리는 가져오기로만 설정됩니다");
        verify(productListingRepository, never()).save(any());
    }

    @Test
    void updateCategorySource_alreadyFollowingMaster_isIdempotent() {
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(cell(null, null)));
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("A").build()));
        givenSaveEchoes();

        ListingCategorySourceResponse response = service.updateCategorySource(LISTING_ID, true);

        // 비울 것이 없으면 옵션 UPDATE 를 만들지 않는다.
        verify(productListingOptionRepository, never()).saveAll(any());
        assertThat(response.getPreviousCategoryCode()).isNull();
        assertThat(response.isUseMasterCategory()).isTrue();
    }
}
