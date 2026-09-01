package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.domain.ImageOp;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.ProcessingPreset;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;

/**
 * Real detail generator (FEATURE_2608_06 / Step 2-2): composes textBindings (fieldValues ?? first-BOM
 * product) + zone image URLs + the default template and delegates to {@link DetailHtmlRenderer}. Pure
 * composition, so a Mockito unit test with a captured {@code render(...)} call covers it.
 */
@ExtendWith(MockitoExtension.class)
class TemplateDetailContentGeneratorTest {

    @Mock private ChannelTemplateResolver channelTemplateResolver;
    @Mock private ProductImageUrlResolver productImageUrlResolver;
    @Mock private MasterImageZoneAssignmentRepository masterImageZoneAssignmentRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private DetailHtmlRenderer detailHtmlRenderer;
    @Mock private DetailFontResolver detailFontResolver;
    @Mock private ImageProcessor imageProcessor;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ProductImageLoader productImageLoader;
    @InjectMocks private TemplateDetailContentGenerator generator;

    @BeforeEach
    void stubFonts() {
        // Font resolution is covered by DetailFontResolverTest; here it must simply not be null.
        lenient().when(detailFontResolver.resolve(any())).thenReturn(Map.of());
    }

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
        given(masterImageZoneAssignmentRepository.findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of(
                        assignment("product_photos", 0, "u0.jpg"),
                        assignment("product_photos", 1, "u1.jpg"),
                        // A __source__ mapping (cover photo) must be excluded from detail zones.
                        assignment(MasterImageZoneAssignment.SOURCE_ZONE, 0, "cover.jpg")));
        // Effective URL resolution: edited entries here simply return their own imageUrl.
        given(productImageUrlResolver.resolve(any()))
                .willAnswer(inv -> ((MasterProductImage) inv.getArgument(0)).getImageUrl());
        given(detailHtmlRenderer.render(any(), any(), any(), any())).willReturn("<html/>");

        String result = generator.generate(cell);

        assertThat(result).isEqualTo("<html/>");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> textCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> zoneCaptor = ArgumentCaptor.forClass(Map.class);
        verify(detailHtmlRenderer, times(1)).render(any(), textCaptor.capture(), zoneCaptor.capture(), any());
        assertThat(textCaptor.getValue()).containsEntry("productName", "P");
        assertThat(zoneCaptor.getValue().get("product_photos")).containsExactly("u0.jpg", "u1.jpg");
        // __source__ (cover photo) is not a detail zone.
        assertThat(zoneCaptor.getValue()).doesNotContainKey(MasterImageZoneAssignment.SOURCE_ZONE);
    }

    /** A mapping carrying a pool image with the given URL. */
    private MasterImageZoneAssignment assignment(String zoneId, int sortOrder, String url) {
        return MasterImageZoneAssignment.builder()
                .zoneId(zoneId).sortOrder(sortOrder)
                .image(MasterProductImage.builder().imageUrl(url).build())
                .build();
    }

    @Test
    void generate_noFieldValue_derivesFromFirstBomProduct() {
        MasterProduct master = MasterProduct.builder().id(MASTER_ID).name("마스터").build(); // no fieldValues
        ProductListing cell = ProductListing.builder().id(CELL_ID).masterProduct(master).build();

        given(channelTemplateResolver.resolveDetail(any())).willReturn(template());
        given(masterImageZoneAssignmentRepository.findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(CELL_ID))
                .willReturn(List.of(ProductListingOption.builder().id(OPTION_ID).optionName("기본").build()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder()
                        .product(Product.builder().productName("Q").brand("B").build()).quantity(1).build()));
        given(detailHtmlRenderer.render(any(), any(), any(), any())).willReturn("<html/>");

        generator.generate(cell);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> textCaptor = ArgumentCaptor.forClass(Map.class);
        verify(detailHtmlRenderer).render(any(), textCaptor.capture(), any(), any());
        assertThat(textCaptor.getValue()).containsEntry("productName", "Q");
    }

    @Test
    void generate_nullMaster_returnsEmpty_rendererNotCalled() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).build(); // no master

        assertThat(generator.generate(cell)).isEmpty();
        verify(detailHtmlRenderer, never()).render(any(), any(), any(), any());
    }

    // ---- image processing preset (FEATURE_2608_08) ----

    private DetailTemplate templateWithPreset() {
        ProcessingPreset preset = ProcessingPreset.builder().id(5L).name("W").active(true)
                .operations(List.of(ImageOp.builder().type("overlay").assetStorageKey("wm.png").build()))
                .build();
        return DetailTemplate.builder().id(3L).name("기본").active(true).isDefault(true)
                .imageProcessingPreset(preset)
                .blocks(List.of(DetailBlock.builder().type("imageZone").bind("product_photos").build()))
                .build();
    }

    @Test
    void generate_presetWithOps_compositesEachZoneImage_swapsUrls() {
        MasterProduct master = MasterProduct.builder().id(MASTER_ID).name("마스터").build();
        ProductListing cell = ProductListing.builder().id(CELL_ID).masterProduct(master).build();

        given(channelTemplateResolver.resolveDetail(any())).willReturn(templateWithPreset());
        given(masterImageZoneAssignmentRepository.findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of(
                        assignment("product_photos", 0, "u0.jpg"),
                        assignment("product_photos", 1, "u1.jpg")));
        given(productImageUrlResolver.resolve(any()))
                .willAnswer(inv -> ((MasterProductImage) inv.getArgument(0)).getImageUrl());
        given(productImageLoader.loadUrl(any())).willReturn(new byte[]{1});
        given(imageProcessor.process(any(), any())).willReturn(new byte[]{2});
        // Return a distinct URL keyed on the (unique) filename so the swap is observable.
        given(imageStorageService.uploadBytes(any(), eq("master-detail"), any(), eq("image/jpeg")))
                .willAnswer(inv -> "out/" + inv.getArgument(2));
        given(detailHtmlRenderer.render(any(), any(), any(), any())).willReturn("<html/>");

        generator.generate(cell);

        verify(imageProcessor, times(2)).process(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> zoneCaptor = ArgumentCaptor.forClass(Map.class);
        verify(detailHtmlRenderer).render(any(), any(), zoneCaptor.capture(), any());
        // URLs swapped to the freshly uploaded composites (filename = {master}_{preset}_{zone}_{index}.jpg).
        assertThat(zoneCaptor.getValue().get("product_photos"))
                .containsExactly("out/1_5_product_photos_0.jpg", "out/1_5_product_photos_1.jpg");
    }

    @Test
    void generate_emptyOps_passesUrlsVerbatim_processorNotCalled() {
        ProcessingPreset emptyPreset = ProcessingPreset.builder().id(5L).name("W").active(true)
                .operations(List.of()).build();
        DetailTemplate template = DetailTemplate.builder().id(3L).name("기본").active(true).isDefault(true)
                .imageProcessingPreset(emptyPreset)
                .blocks(List.of(DetailBlock.builder().type("imageZone").bind("product_photos").build()))
                .build();
        MasterProduct master = MasterProduct.builder().id(MASTER_ID).name("마스터").build();
        ProductListing cell = ProductListing.builder().id(CELL_ID).masterProduct(master).build();

        given(channelTemplateResolver.resolveDetail(any())).willReturn(template);
        given(masterImageZoneAssignmentRepository.findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of(assignment("product_photos", 0, "u0.jpg")));
        given(productImageUrlResolver.resolve(any()))
                .willAnswer(inv -> ((MasterProductImage) inv.getArgument(0)).getImageUrl());
        given(detailHtmlRenderer.render(any(), any(), any(), any())).willReturn("<html/>");

        generator.generate(cell);

        verify(imageProcessor, never()).process(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> zoneCaptor = ArgumentCaptor.forClass(Map.class);
        verify(detailHtmlRenderer).render(any(), any(), zoneCaptor.capture(), any());
        assertThat(zoneCaptor.getValue().get("product_photos")).containsExactly("u0.jpg"); // verbatim
    }
    // Note: "no default template" now throws in ChannelTemplateResolver (covered by ChannelTemplateResolverTest),
    // so the generator no longer has a null-template branch of its own.
}
