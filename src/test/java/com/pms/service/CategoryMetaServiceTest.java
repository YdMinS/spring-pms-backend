package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.dto.response.CategoryMetaResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.service.listing.category.CategoryAttribute;
import com.pms.service.listing.category.CategoryMetaAdapter;
import com.pms.service.listing.category.CategoryMetaResolver;
import com.pms.service.listing.category.CategoryMetaSchema;
import com.pms.service.listing.category.CategoryNotice;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Category meta service (FEATURE_2608_06 / 47): GET merges schema + current master values (404 missing master,
 * 400 missing mapping, empty schema still 200); PATCH saves the values without touching the meta resolver.
 */
@ExtendWith(MockitoExtension.class)
class CategoryMetaServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private CategoryMetaResolver metaResolver;
    @Mock private CategoryMetaAdapter metaAdapter;
    @InjectMocks private CategoryMetaServiceImpl service;

    private static final Long MASTER_ID = 10L;
    private static final Long CATEGORY_ID = 5L;

    private MasterProduct master(Map<String, String> attributeValues) {
        return MasterProduct.builder().id(MASTER_ID).name("마스터").active(true)
                .category(Category.builder().id(CATEGORY_ID).build())
                .categoryAttributes(attributeValues).build();
    }

    private MarketplaceAccount account() {
        return MarketplaceAccount.builder().vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build();
    }

    @Test
    void getMeta_returnsSchemaAndCurrentValues() {
        given(masterProductRepository.findScopedById(MASTER_ID))
                .willReturn(Optional.of(master(Map.of("원산지", "국내산"))));
        given(masterChannelConfigService.resolvePlatformCategoryCode(CATEGORY_ID, "COUPANG"))
                .willReturn("1001");
        given(marketplaceAccountRepository.findFirstByPlatformAndIsActiveTrue("COUPANG"))
                .willReturn(Optional.of(account()));
        given(metaResolver.resolve("COUPANG")).willReturn(metaAdapter);
        given(metaAdapter.getMeta(any(), eq("1001"))).willReturn(new CategoryMetaSchema(
                List.of(new CategoryAttribute("원산지", true, "TEXT", List.of())),
                List.of(new CategoryNotice("제품소재", "제품소재", true, "의류"))));

        CategoryMetaResponse response = service.getMeta(MASTER_ID, "COUPANG");

        assertThat(response.getAttributes()).extracting(CategoryAttribute::name).containsExactly("원산지");
        assertThat(response.getNotices()).extracting(CategoryNotice::key).containsExactly("제품소재");
        assertThat(response.getValues().getAttributes()).containsEntry("원산지", "국내산");
        assertThat(response.getValues().getNoticeGroup()).isNull();   // unset = screen infers
    }

    @Test
    void getMeta_missingMaster_throws404() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMeta(MASTER_ID, "COUPANG"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMeta_missingMapping_throws400() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master(Map.of())));
        given(masterChannelConfigService.resolvePlatformCategoryCode(CATEGORY_ID, "COUPANG"))
                .willThrow(new IllegalArgumentException("COUPANG 카테고리 매핑 미설정"));

        assertThatThrownBy(() -> service.getMeta(MASTER_ID, "COUPANG"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getMeta_emptySchema_returns200WithEmptyLists() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master(null)));
        given(masterChannelConfigService.resolvePlatformCategoryCode(CATEGORY_ID, "NAVER"))
                .willReturn("N1");
        given(marketplaceAccountRepository.findFirstByPlatformAndIsActiveTrue("NAVER"))
                .willReturn(Optional.of(account()));
        given(metaResolver.resolve("NAVER")).willReturn(metaAdapter);
        given(metaAdapter.getMeta(any(), eq("N1")))
                .willReturn(new CategoryMetaSchema(List.of(), List.of()));

        CategoryMetaResponse response = service.getMeta(MASTER_ID, "NAVER");

        assertThat(response.getAttributes()).isEmpty();
        assertThat(response.getNotices()).isEmpty();
        assertThat(response.getValues().getAttributes()).isEmpty();
    }

    // ---- getSchema (57 — category-scoped schema, no master, no values) ----

    @Test
    void getSchema_returnsResolverSchema() {
        given(masterChannelConfigService.resolvePlatformCategoryCode(CATEGORY_ID, "COUPANG")).willReturn("1001");
        given(marketplaceAccountRepository.findFirstByPlatformAndIsActiveTrue("COUPANG"))
                .willReturn(Optional.of(account()));
        given(metaResolver.resolve("COUPANG")).willReturn(metaAdapter);
        CategoryMetaSchema schema = new CategoryMetaSchema(
                List.of(new CategoryAttribute("원산지", true, "TEXT", List.of())),
                List.of(new CategoryNotice("제품소재", "제품소재", true, "의류")));
        given(metaAdapter.getMeta(any(), eq("1001"))).willReturn(schema);

        assertThat(service.getSchema(CATEGORY_ID, "COUPANG")).isSameAs(schema);
    }

    @Test
    void getSchema_missingMapping_throws400() {
        given(masterChannelConfigService.resolvePlatformCategoryCode(CATEGORY_ID, "COUPANG"))
                .willThrow(new IllegalArgumentException("COUPANG 카테고리 매핑 미설정"));

        assertThatThrownBy(() -> service.getSchema(CATEGORY_ID, "COUPANG"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getSchema_noActiveAccount_throws400() {
        given(masterChannelConfigService.resolvePlatformCategoryCode(CATEGORY_ID, "COUPANG")).willReturn("1001");
        given(marketplaceAccountRepository.findFirstByPlatformAndIsActiveTrue("COUPANG"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSchema(CATEGORY_ID, "COUPANG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("활성 계정 없음");
    }

    @Test
    void getSchema_emptySchema_returnsEmptyLists() {
        given(masterChannelConfigService.resolvePlatformCategoryCode(CATEGORY_ID, "NAVER")).willReturn("N1");
        given(marketplaceAccountRepository.findFirstByPlatformAndIsActiveTrue("NAVER"))
                .willReturn(Optional.of(account()));
        given(metaResolver.resolve("NAVER")).willReturn(metaAdapter);
        given(metaAdapter.getMeta(any(), eq("N1"))).willReturn(new CategoryMetaSchema(List.of(), List.of()));

        CategoryMetaSchema schema = service.getSchema(CATEGORY_ID, "NAVER");

        assertThat(schema.attributes()).isEmpty();
        assertThat(schema.notices()).isEmpty();
    }

    @Test
    void updateCategoryAttributes_savesValues_noRegeneration() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master(null)));
        Map<String, String> attributes = Map.of("원산지", "국내산");
        Map<String, String> notices = Map.of("제품소재", "면 100%");

        service.updateCategoryAttributes(MASTER_ID, attributes, notices, "가공식품");

        ArgumentCaptor<MasterProduct> captor = ArgumentCaptor.forClass(MasterProduct.class);
        verify(masterProductRepository).save(captor.capture());
        assertThat(captor.getValue().getCategoryAttributes()).isEqualTo(attributes);
        assertThat(captor.getValue().getCategoryNotices()).isEqualTo(notices);
        assertThat(captor.getValue().getCategoryNoticeGroup()).isEqualTo("가공식품");
        verify(metaResolver, never()).resolve(any());   // attributes are not a thumbnail/detail binding key
    }

    @Test
    void updateCategoryAttributes_blankNoticeGroup_savesNull() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master(null)));

        service.updateCategoryAttributes(MASTER_ID, Map.of(), Map.of(), "");
        service.updateCategoryAttributes(MASTER_ID, Map.of(), Map.of(), "   ");

        ArgumentCaptor<MasterProduct> captor = ArgumentCaptor.forClass(MasterProduct.class);
        verify(masterProductRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(MasterProduct::getCategoryNoticeGroup)
                .containsExactly(null, null);
    }

    @Test
    void getMeta_returnsStoredNoticeGroup() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(
                master(null).toBuilder().categoryNoticeGroup("가공식품").build()));
        given(masterChannelConfigService.resolvePlatformCategoryCode(CATEGORY_ID, "COUPANG"))
                .willReturn("1001");
        given(marketplaceAccountRepository.findFirstByPlatformAndIsActiveTrue("COUPANG"))
                .willReturn(Optional.of(account()));
        given(metaResolver.resolve("COUPANG")).willReturn(metaAdapter);
        given(metaAdapter.getMeta(any(), eq("1001")))
                .willReturn(new CategoryMetaSchema(List.of(), List.of()));

        CategoryMetaResponse response = service.getMeta(MASTER_ID, "COUPANG");

        assertThat(response.getValues().getNoticeGroup()).isEqualTo("가공식품");
    }
}
