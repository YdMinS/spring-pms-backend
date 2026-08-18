package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real detail generator (FEATURE_2608_06 / Step 2-2): composes textBindings (fieldValues ?? first-BOM
 * product) + zone image URLs + the default template and delegates to {@link DetailHtmlRenderer}. Pure
 * composition, so a Mockito unit test with a captured {@code render(...)} call covers it.
 */
@ExtendWith(MockitoExtension.class)
class TemplateDetailContentGeneratorTest {

    @Mock private ChannelTemplateResolver channelTemplateResolver;
    @Mock private MasterProductImageRepository masterProductImageRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private DetailHtmlRenderer detailHtmlRenderer;
    @InjectMocks private TemplateDetailContentGenerator generator;

    private static final Long MASTER_ID = 1L;
    private static final Long CELL_ID = 100L;
    private static final Long OPTION_ID = 50L;

    private DetailTemplate template() {
        return DetailTemplate.builder().id(3L).name("기본").active(true).isDefault(true)
                .blocks(List.of(
                        DetailBlock.builder().type("text").bind("productName").build(),
                        DetailBlock.builder().type("imageZone").bind("product_photos").build()))
                .build();
    }

    @Test
    void generate_composesBindingsAndZoneImages_delegatesToRendererOnce() {
        MasterProduct master = MasterProduct.builder().id(MASTER_ID).name("마스터")
                .fieldValues(Map.of("productName", "P")).build();
        ProductListing cell = ProductListing.builder().id(CELL_ID).masterProduct(master).build();

        given(channelTemplateResolver.resolveDetail(any())).willReturn(template());
        given(masterProductImageRepository.findByMasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of(
                        MasterProductImage.builder().zoneId("product_photos").sortOrder(0).imageUrl("u0.jpg").build(),
                        MasterProductImage.builder().zoneId("product_photos").sortOrder(1).imageUrl("u1.jpg").build()));
        given(detailHtmlRenderer.render(any(), any(), any())).willReturn("<html/>");

        String result = generator.generate(cell);

        assertThat(result).isEqualTo("<html/>");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> textCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> zoneCaptor = ArgumentCaptor.forClass(Map.class);
        verify(detailHtmlRenderer, times(1)).render(any(), textCaptor.capture(), zoneCaptor.capture());
        assertThat(textCaptor.getValue()).containsEntry("productName", "P");
        assertThat(zoneCaptor.getValue().get("product_photos")).containsExactly("u0.jpg", "u1.jpg");
    }

    @Test
    void generate_noFieldValue_derivesFromFirstBomProduct() {
        MasterProduct master = MasterProduct.builder().id(MASTER_ID).name("마스터").build(); // no fieldValues
        ProductListing cell = ProductListing.builder().id(CELL_ID).masterProduct(master).build();

        given(channelTemplateResolver.resolveDetail(any())).willReturn(template());
        given(masterProductImageRepository.findByMasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(CELL_ID))
                .willReturn(List.of(ProductListingOption.builder().id(OPTION_ID).optionName("기본").build()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder()
                        .product(Product.builder().productName("Q").brand("B").build()).quantity(1).build()));
        given(detailHtmlRenderer.render(any(), any(), any())).willReturn("<html/>");

        generator.generate(cell);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> textCaptor = ArgumentCaptor.forClass(Map.class);
        verify(detailHtmlRenderer).render(any(), textCaptor.capture(), any());
        assertThat(textCaptor.getValue()).containsEntry("productName", "Q");
    }

    @Test
    void generate_nullMaster_returnsEmpty_rendererNotCalled() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).build(); // no master

        assertThat(generator.generate(cell)).isEmpty();
        verify(detailHtmlRenderer, never()).render(any(), any(), any());
    }
    // Note: "no default template" now throws in ChannelTemplateResolver (covered by ChannelTemplateResolverTest),
    // so the generator no longer has a null-template branch of its own.
}
