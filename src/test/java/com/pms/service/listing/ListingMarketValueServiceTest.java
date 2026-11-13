package com.pms.service.listing;

import com.pms.domain.ListingMarketValueGroup;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingMarketValuesResponse;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.MasterChannelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The 「마켓 값 사용」 groups (FEATURE_2611_27 / R3 · R4 · R6 · R7): an import stores the groups the market sends from the
 * cell's own values and turns their flags on; [마스터 값으로 바꾸기] clears one group and turns its flag off.
 */
@ExtendWith(MockitoExtension.class)
class ListingMarketValueServiceTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ListingChannelResolver resolver;
    @Mock private ListingAssetService listingAssetService;
    @Mock private TagMergeService tagMergeService;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private ListingChannel channel;
    @InjectMocks private ListingMarketValueServiceImpl service;

    private static final Long LISTING_ID = 50L;

    private ProductListing cell;
    private ProductListingOption o11;
    private ProductListingOption o12;

    @BeforeEach
    void seed() {
        cell = ProductListing.builder().id(LISTING_ID).platform(Platform.COUPANG).name("셀")
                .masterProduct(MasterProduct.builder().id(1L).tags(List.of("생수")).build()).build();
        o11 = ProductListingOption.builder().id(11L).optionName("6입").platformOptionId("8123").build();
        o12 = ProductListingOption.builder().id(12L).optionName("12입").platformOptionId(null).build();
        givenCell(cell);
        lenient().when(productListingOptionRepository.findByProductListingId(LISTING_ID)).thenReturn(List.of(o11, o12));
        lenient().when(resolver.resolve(Platform.COUPANG)).thenReturn(channel);
    }

    private void givenCell(ProductListing value) {
        lenient().when(productListingRepository.findScopedById(LISTING_ID)).thenReturn(Optional.of(value));
    }

    private void givenSaveEchoes() {
        given(productListingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    private static ImportedProduct.Option readOption(String name, String vendorItemId, Map<String, String> attributes,
                                                     Map<String, String> notices) {
        return new ImportedProduct.Option(name, vendorItemId, null, null, null, null, attributes, notices);
    }

    private static ImportedProduct fetched(List<ImportedProduct.Option> options, List<String> tags, String noticeGroup,
                                           Map<String, String> shipping) {
        return new ImportedProduct("상품", null, null, null, tags, noticeGroup, List.of(), List.of(), options,
                null, null, null, null, List.of(), null, shipping);
    }

    private static List<ImportedProduct.Option> twoReadOptions() {
        return List.of(
                readOption("6입", "8123", Map.of("수량", "6개"), Map.of("품목 또는 명칭", "생수")),
                readOption("12입", null, Map.of("수량", "12개"), Map.of()));
    }

    private ProductListing savedCell() {
        ArgumentCaptor<ProductListing> captor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<ProductListingOption> savedOptions() {
        ArgumentCaptor<List<ProductListingOption>> captor = ArgumentCaptor.forClass(List.class);
        verify(productListingOptionRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ applyImported

    @Test
    void applyImported_allGroupsRead_storesWholeValuesAndTurnsThreeFlagsOn() {
        given(channel.marketValueGroups()).willReturn(EnumSet.allOf(ListingMarketValueGroup.class));
        given(tagMergeService.dedup(List.of("생수", "물", "생수"))).willReturn(List.of("생수", "물"));
        ImportedProduct fetched = fetched(twoReadOptions(), List.of("생수", "물", "생수"), "가공식품",
                Map.of("deliveryCharge", "2500", "returnCenterCode", "RC1"));

        Set<ListingMarketValueGroup> on = service.applyImported(LISTING_ID, fetched);

        assertThat(on).containsExactlyInAnyOrder(ListingMarketValueGroup.ATTRIBUTES, ListingMarketValueGroup.TAGS,
                ListingMarketValueGroup.SHIPPING);
        List<ProductListingOption> options = savedOptions();
        assertThat(options).hasSize(2);
        ProductListingOption saved11 = options.stream().filter(o -> o.getId() == 11L).findFirst().orElseThrow();
        ProductListingOption saved12 = options.stream().filter(o -> o.getId() == 12L).findFirst().orElseThrow();
        assertThat(saved11.getCategoryAttributes()).isEqualTo(Map.of("수량", "6개"));
        assertThat(saved11.getCategoryNotices()).isEqualTo(Map.of("품목 또는 명칭", "생수"));
        assertThat(saved12.getCategoryAttributes()).isEqualTo(Map.of("수량", "12개"));
        assertThat(saved12.getCategoryNotices()).isNull();
        ProductListing saved = savedCell();
        assertThat(saved.getTags()).containsExactly("생수", "물");
        assertThat(saved.getCategoryNoticeGroup()).isEqualTo("가공식품");
        assertThat(saved.isUsesMarketAttributes()).isTrue();
        assertThat(saved.isUsesMarketTags()).isTrue();
        assertThat(saved.isUsesMarketShipping()).isTrue();
        verify(listingAssetService).updateShippingOverride(LISTING_ID,
                Map.of("deliveryCharge", "2500", "returnCenterCode", "RC1"));
    }

    @Test
    void applyImported_marketSendsOnlyShipping_keepsOtherGroupsOff() {
        given(channel.marketValueGroups()).willReturn(Set.of(ListingMarketValueGroup.SHIPPING));
        ImportedProduct fetched = fetched(twoReadOptions(), List.of("물"), null,
                Map.of("elevenst.dlvCst1", "3000"));

        Set<ListingMarketValueGroup> on = service.applyImported(LISTING_ID, fetched);

        assertThat(on).containsExactly(ListingMarketValueGroup.SHIPPING);
        verify(productListingOptionRepository, never()).saveAll(any());
        verify(productListingOptionRepository, never()).save(any());
        ProductListing saved = savedCell();
        assertThat(saved.getTags()).isNull();
        assertThat(saved.getCategoryNoticeGroup()).isNull();
        assertThat(saved.isUsesMarketAttributes()).isFalse();
        assertThat(saved.isUsesMarketTags()).isFalse();
        assertThat(saved.isUsesMarketShipping()).isTrue();
        verify(listingAssetService).updateShippingOverride(LISTING_ID, Map.of("elevenst.dlvCst1", "3000"));
    }

    @Test
    void applyImported_nothingRead_turnsNothingOn() {
        given(channel.marketValueGroups()).willReturn(EnumSet.allOf(ListingMarketValueGroup.class));
        ImportedProduct fetched = fetched(List.of(readOption("6입", "8123", Map.of(), Map.of())), List.of(), null,
                Map.of());

        Set<ListingMarketValueGroup> on = service.applyImported(LISTING_ID, fetched);

        assertThat(on).isEmpty();
        verify(productListingOptionRepository, never()).saveAll(any());
        verify(productListingOptionRepository, never()).save(any());
        verify(listingAssetService, never()).updateShippingOverride(anyLong(), any());
        ProductListing saved = savedCell();
        assertThat(saved.isUsesMarketAttributes()).isFalse();
        assertThat(saved.isUsesMarketTags()).isFalse();
        assertThat(saved.isUsesMarketShipping()).isFalse();
        assertThat(saved.getTags()).isNull();
    }

    @Test
    void applyImported_shippingKeepsExistingOverrideKeys() {
        givenCell(cell.toBuilder()
                .shippingOverride(Map.of("returnCharge", "5000", "deliveryCharge", "3000")).build());
        given(channel.marketValueGroups()).willReturn(Set.of(ListingMarketValueGroup.SHIPPING));
        ImportedProduct fetched = fetched(twoReadOptions(), List.of("물"), null, Map.of("deliveryCharge", "2500"));

        service.applyImported(LISTING_ID, fetched);

        verify(listingAssetService).updateShippingOverride(LISTING_ID,
                Map.of("returnCharge", "5000", "deliveryCharge", "2500"));
    }

    @Test
    void applyImported_shippingSaveFails_rollsBackByThrowing() {
        given(channel.marketValueGroups()).willReturn(EnumSet.allOf(ListingMarketValueGroup.class));
        given(tagMergeService.dedup(any())).willReturn(List.of("생수", "물"));
        willThrow(new IllegalArgumentException("배송설정 오류"))
                .given(listingAssetService).updateShippingOverride(eq(LISTING_ID), any());
        ImportedProduct fetched = fetched(twoReadOptions(), List.of("생수", "물", "생수"), "가공식품",
                Map.of("deliveryCharge", "2500", "returnCenterCode", "RC1"));

        assertThatThrownBy(() -> service.applyImported(LISTING_ID, fetched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송설정 오류");
    }

    // ------------------------------------------------------------------ useMasterValues

    @Test
    void useMasterValues_attributes_clearsOptionValuesAndGroup() {
        givenCell(cell.toBuilder().usesMarketAttributes(true).categoryNoticeGroup("가공식품").build());
        o11 = o11.toBuilder().categoryAttributes(Map.of("수량", "6개"))
                .categoryNotices(Map.of("품목 또는 명칭", "생수")).build();
        given(productListingOptionRepository.findByProductListingId(LISTING_ID)).willReturn(List.of(o11, o12));
        given(masterChannelConfigService.resolveChannelCategory(any()))
                .willReturn(new MasterChannelConfigService.ChannelCategory(
                        PlatformCategory.builder().platform(Platform.COUPANG).code("58630").build(), false));
        givenSaveEchoes();

        ListingMarketValuesResponse response = service.useMasterValues(LISTING_ID, ListingMarketValueGroup.ATTRIBUTES);

        assertThat(savedOptions()).singleElement().satisfies(option -> {
            assertThat(option.getId()).isEqualTo(11L);
            assertThat(option.getCategoryAttributes()).isNull();
            assertThat(option.getCategoryNotices()).isNull();
        });
        ProductListing saved = savedCell();
        assertThat(saved.isUsesMarketAttributes()).isFalse();
        assertThat(saved.getCategoryNoticeGroup()).isNull();
        assertThat(response.isUsesMarketAttributes()).isFalse();
    }

    @Test
    void useMasterValues_attributesOnOwnCategory_isRefused() {
        givenCell(cell.toBuilder().usesMarketAttributes(true).categoryNoticeGroup("가공식품").build());
        given(masterChannelConfigService.resolveChannelCategory(any()))
                .willReturn(new MasterChannelConfigService.ChannelCategory(
                        PlatformCategory.builder().platform(Platform.COUPANG).code("58630").build(), true));

        assertThatThrownBy(() -> service.useMasterValues(LISTING_ID, ListingMarketValueGroup.ATTRIBUTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("자기 카테고리를 쓰는 판매채널은 [마스터 카테고리로 변경]을 쓰세요 — 속성·고시도 함께 마스터 값으로 바뀝니다");
        verify(productListingRepository, never()).save(any());
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    @Test
    void useMasterValues_tags_clearsTagsAndFlag() {
        givenCell(cell.toBuilder().usesMarketTags(true).tags(List.of("생수", "물")).build());
        givenSaveEchoes();

        ListingMarketValuesResponse response = service.useMasterValues(LISTING_ID, ListingMarketValueGroup.TAGS);

        ProductListing saved = savedCell();
        assertThat(saved.getTags()).isNull();
        assertThat(saved.isUsesMarketTags()).isFalse();
        assertThat(response.isUsesMarketTags()).isFalse();
    }

    @Test
    void useMasterValues_shipping_clearsOverride() {
        givenCell(cell.toBuilder().usesMarketShipping(true).shippingOverride(Map.of("deliveryCharge", "2500")).build());
        givenSaveEchoes();

        service.useMasterValues(LISTING_ID, ListingMarketValueGroup.SHIPPING);

        ProductListing saved = savedCell();
        assertThat(saved.getShippingOverride()).isNull();
        assertThat(saved.isUsesMarketShipping()).isFalse();
    }

    @Test
    void useMasterValues_groupNotInUse_isRefused() {
        assertThatThrownBy(() -> service.useMasterValues(LISTING_ID, ListingMarketValueGroup.SHIPPING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("마켓 값을 쓰지 않는 묶음입니다");
        verify(productListingRepository, never()).save(any());
    }
}
