package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.ProductImage;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.TemplateField;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import com.pms.exception.ResourceNotFoundException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@code regenerateAssets} seam (FEATURE_2608_06 / 3b-2): base photo = master override (preferred) else
 * first BOM product; text bindings prefer master fieldValues; renderer + uploadBytes called once; option
 * sellingPrice written; GeneratedProductData upserted (new / in-place); detail stub non-blank.
 */
@ExtendWith(MockitoExtension.class)
class ListingAssetServiceTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterImageZoneAssignmentRepository masterImageZoneAssignmentRepository;
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private ChannelTemplateResolver channelTemplateResolver;
    @Mock private ProductImageUrlResolver productImageUrlResolver;
    @Mock private ThumbnailRenderer thumbnailRenderer;
    @Mock private ProductImageLoader productImageLoader;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ImageValidator imageValidator;
    @Mock private PriceCalculator priceCalculator;
    @Mock private DetailContentGenerator detailContentGenerator;
    @InjectMocks private ListingAssetServiceImpl service;

    private static final Long CELL_ID = 100L;
    private static final Long OPTION_ID = 50L;

    private Product product() {
        return Product.builder().id(9L).productName("운동화").brand("나이키")
                .price(new BigDecimal("5000")).imageUrl("products/p.jpg").build();
    }

    private ProductListingOption option() {
        return ProductListingOption.builder().id(OPTION_ID).optionName("기본")
                .sellingPrice(BigDecimal.ZERO).build();
    }

    private ThumbnailTemplate template() {
        return ThumbnailTemplate.builder().id(3L).name("기본").canvasWidth(300).canvasHeight(300)
                .fields(List.of(TemplateField.builder().key("productName").label("상품명").defaultValue("").build()))
                .build();
    }

    private void commonRenderStubs() {
        given(channelTemplateResolver.resolveThumbnail(any())).willReturn(template());
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{1, 2, 3});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/generated.jpg");
        given(priceCalculator.calculatePrice(any(), any(), any())).willReturn(new BigDecimal("10670"));
        given(detailContentGenerator.generate(any())).willReturn("<p>운동화</p>");
        given(generatedProductDataRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void regenerateAssets_masterOverride_usesOverrideImage_fieldValuesWin_insertsNew() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터")
                .sourceImageUrl("https://cdn/override.jpg")
                .fieldValues(Map.of("productName", "직접입력상품")).build();
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀")
                .masterProduct(master).build();

        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(productImageLoader.loadUrl("https://cdn/override.jpg")).willReturn(new byte[]{9});
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.empty());
        commonRenderStubs();

        service.regenerateAssets(cell);

        // Base photo: master override taken, BOM product image NOT loaded.
        verify(productImageLoader).loadUrl("https://cdn/override.jpg");
        verify(productImageLoader, never()).load(any());

        // Text bindings: fieldValues override wins for the reserved key.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> bindings = ArgumentCaptor.forClass(Map.class);
        verify(thumbnailRenderer).render(any(), bindings.capture(), any());
        assertThat(bindings.getValue()).containsEntry("productName", "직접입력상품");

        verify(imageStorageService, times(1)).uploadBytes(any(), anyString(), anyString(), anyString());

        // Option sellingPrice written back with the calculated price.
        ArgumentCaptor<ProductListingOption> optionCaptor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(optionCaptor.capture());
        assertThat(optionCaptor.getValue().getSellingPrice()).isEqualByComparingTo("10670");

        // Upsert: new row (no id), detail stub non-blank.
        ArgumentCaptor<GeneratedProductData> dataCaptor = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(dataCaptor.capture());
        assertThat(dataCaptor.getValue().getId()).isNull();
        assertThat(dataCaptor.getValue().getProductListing()).isSameAs(cell);
        assertThat(dataCaptor.getValue().getDetailHtml()).isNotBlank();
    }

    @Test
    void regenerateAssets_sourceReferenceEntry_usesLiveProductImageUrl() {
        // The master cover is a __source__ mapping onto a REFERENCE pool entry (live-links a product slot).
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터").build();
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀")
                .masterProduct(master).build();
        MasterProductImage refEntry = MasterProductImage.builder()
                .id(20L).masterProduct(master)
                .productImage(ProductImage.builder().id(9L).imageUrl("products/live.jpg").build())
                .build(); // imageUrl null — reference entry
        given(masterImageZoneAssignmentRepository
                .findByImage_MasterProductIdAndZoneIdOrderBySortOrderAsc(1L, MasterImageZoneAssignment.SOURCE_ZONE))
                .willReturn(List.of(MasterImageZoneAssignment.builder()
                        .image(refEntry).zoneId(MasterImageZoneAssignment.SOURCE_ZONE).sortOrder(0).build()));
        given(productImageUrlResolver.resolve(refEntry)).willReturn("products/live.jpg");

        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(productImageLoader.loadUrl("products/live.jpg")).willReturn(new byte[]{9});
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.empty());
        commonRenderStubs();

        service.regenerateAssets(cell);

        // Base photo resolved through the reference → the live product image URL (not a stale copy).
        verify(productImageLoader).loadUrl("products/live.jpg");
        verify(productImageLoader, never()).load(any());
    }

    @Test
    void regenerateAssets_noOverride_usesFirstBomProductImage_andProductInfoFallback() {
        Product product = product();
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();

        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product).quantity(1).build()));
        given(productImageLoader.load(product)).willReturn(new byte[]{7});
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.empty());
        commonRenderStubs();

        service.regenerateAssets(cell);

        // Base photo: first BOM product image loaded (no master override).
        verify(productImageLoader).load(product);
        verify(productImageLoader, never()).loadUrl(any());

        // No fieldValues → reserved key falls back to the registered product's name.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> bindings = ArgumentCaptor.forClass(Map.class);
        verify(thumbnailRenderer).render(any(), bindings.capture(), any());
        assertThat(bindings.getValue()).containsEntry("productName", "운동화");
    }

    @Test
    void regenerateAssets_existingData_updatesInPlace() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();

        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(productImageLoader.load(any())).willReturn(new byte[]{7});
        GeneratedProductData existing = GeneratedProductData.builder()
                .id(77L).productListing(cell).thumbnailUrl("old.jpg").build();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.of(existing));
        commonRenderStubs();

        service.regenerateAssets(cell);

        ArgumentCaptor<GeneratedProductData> dataCaptor = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(dataCaptor.capture());
        assertThat(dataCaptor.getValue().getId()).isEqualTo(77L);            // same row updated, not inserted
        assertThat(dataCaptor.getValue().getThumbnailUrl()).isEqualTo("thumbnails/generated.jpg");
    }

    // ---- override guard (Step 2-2) ----

    @Test
    void regenerateAssets_manualOverride_preservesDetailHtml_stillRegeneratesThumbnailAndPrice() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();

        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(productImageLoader.load(any())).willReturn(new byte[]{7});
        given(channelTemplateResolver.resolveThumbnail(any())).willReturn(template());
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{1, 2, 3});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/generated.jpg");
        given(priceCalculator.calculatePrice(any(), any(), any())).willReturn(new BigDecimal("10670"));
        GeneratedProductData existing = GeneratedProductData.builder()
                .id(88L).productListing(cell).detailHtml("X")
                .source(GeneratedContentSource.MANUAL_OVERRIDE).build();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.of(existing));
        given(generatedProductDataRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.regenerateAssets(cell);

        // Override cell → generator NOT called, edited detailHtml preserved, source stays MANUAL_OVERRIDE.
        verify(detailContentGenerator, never()).generate(any());
        ArgumentCaptor<GeneratedProductData> dataCaptor = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(dataCaptor.capture());
        assertThat(dataCaptor.getValue().getDetailHtml()).isEqualTo("X");
        assertThat(dataCaptor.getValue().getSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        // Thumbnail + option price are still regenerated.
        assertThat(dataCaptor.getValue().getThumbnailUrl()).isEqualTo("thumbnails/generated.jpg");
        ArgumentCaptor<ProductListingOption> optionCaptor = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(optionCaptor.capture());
        assertThat(optionCaptor.getValue().getSellingPrice()).isEqualByComparingTo("10670");
    }

    // ---- channel field-value override (12) ----

    @Test
    void updateFieldValues_savesOverride_andOverrideWinsInThumbnailBindings() {
        MasterProduct master = MasterProduct.builder().id(1L).name("마스터")
                .fieldValues(Map.of("productName", "마스터상품")).build();
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀")
                .masterProduct(master).build();

        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        given(productListingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(productImageLoader.load(any())).willReturn(new byte[]{7});
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.empty());
        commonRenderStubs();

        service.updateFieldValues(CELL_ID, Map.of("productName", "OVERRIDE"));

        // The override is persisted on the cell.
        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getFieldValues()).containsEntry("productName", "OVERRIDE");

        // Thumbnail bindings use the channel override (not the master value, not the product name).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> bindings = ArgumentCaptor.forClass(Map.class);
        verify(thumbnailRenderer).render(any(), bindings.capture(), any());
        assertThat(bindings.getValue()).containsEntry("productName", "OVERRIDE");
    }

    @Test
    void regenerateAssets_autoSource_regeneratesDetailHtmlFromGenerator() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();

        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(productImageLoader.load(any())).willReturn(new byte[]{7});
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.empty());
        commonRenderStubs();

        service.regenerateAssets(cell);

        ArgumentCaptor<GeneratedProductData> dataCaptor = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(dataCaptor.capture());
        assertThat(dataCaptor.getValue().getDetailHtml()).isEqualTo("<p>운동화</p>");   // generator output
        assertThat(dataCaptor.getValue().getSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    // ---- thumbnail override / clear (25) ----

    @Test
    void overrideThumbnail_replacesUrl_setsManualOverride_detailUntouched() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();
        GeneratedProductData existing = GeneratedProductData.builder()
                .id(88L).productListing(cell).thumbnailUrl("old.jpg")
                .thumbnailSource(GeneratedContentSource.AUTO)
                .detailHtml("DETAIL").source(GeneratedContentSource.MANUAL_OVERRIDE).build();

        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.of(existing));
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/manual.jpg");
        given(generatedProductDataRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "t.jpg", "image/jpeg", new byte[]{1, 2, 3});

        service.overrideThumbnail(CELL_ID, file);

        verify(imageValidator).validate(file);
        ArgumentCaptor<GeneratedProductData> captor = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(captor.capture());
        assertThat(captor.getValue().getThumbnailUrl()).isEqualTo("thumbnails/manual.jpg");
        assertThat(captor.getValue().getThumbnailSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        // detail HTML + its (detail) source are independent and untouched.
        assertThat(captor.getValue().getDetailHtml()).isEqualTo("DETAIL");
        assertThat(captor.getValue().getSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        // Renderer is not used on a manual upload.
        verify(thumbnailRenderer, never()).render(any(), any(), any());
    }

    @Test
    void overrideThumbnail_notYetGenerated_throws404() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "t.jpg", "image/jpeg", new byte[]{1});

        assertThatThrownBy(() -> service.overrideThumbnail(CELL_ID, file))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void regenerateAssets_thumbnailManualOverride_preservesThumbnail_noRerender() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();

        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(priceCalculator.calculatePrice(any(), any(), any())).willReturn(new BigDecimal("10670"));
        given(detailContentGenerator.generate(any())).willReturn("<p>운동화</p>");
        GeneratedProductData existing = GeneratedProductData.builder()
                .id(88L).productListing(cell).thumbnailUrl("kept.jpg")
                .thumbnailSource(GeneratedContentSource.MANUAL_OVERRIDE)
                .source(GeneratedContentSource.AUTO).build();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.of(existing));
        given(generatedProductDataRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.regenerateAssets(cell);

        // Thumbnail override preserved: renderer + upload NOT called, url kept.
        verify(thumbnailRenderer, never()).render(any(), any(), any());
        verify(imageStorageService, never()).uploadBytes(any(), anyString(), anyString(), anyString());
        verify(productImageLoader, never()).load(any());
        ArgumentCaptor<GeneratedProductData> captor = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(captor.capture());
        assertThat(captor.getValue().getThumbnailUrl()).isEqualTo("kept.jpg");
        assertThat(captor.getValue().getThumbnailSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        // Detail (AUTO) is independent → still regenerated by the generator.
        assertThat(captor.getValue().getDetailHtml()).isEqualTo("<p>운동화</p>");
        assertThat(captor.getValue().getSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    @Test
    void regenerateAssets_detailOverride_thumbnailAuto_independentlyPreserved() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();

        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(productImageLoader.load(any())).willReturn(new byte[]{7});
        given(channelTemplateResolver.resolveThumbnail(any())).willReturn(template());
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{1, 2, 3});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/generated.jpg");
        given(priceCalculator.calculatePrice(any(), any(), any())).willReturn(new BigDecimal("10670"));
        GeneratedProductData existing = GeneratedProductData.builder()
                .id(88L).productListing(cell).thumbnailUrl("old.jpg")
                .thumbnailSource(GeneratedContentSource.AUTO)
                .detailHtml("EDITED").source(GeneratedContentSource.MANUAL_OVERRIDE).build();
        given(generatedProductDataRepository.findByProductListingId(CELL_ID)).willReturn(Optional.of(existing));
        given(generatedProductDataRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.regenerateAssets(cell);

        // Detail override preserved (generator not called) while the AUTO thumbnail is re-rendered — independent.
        verify(detailContentGenerator, never()).generate(any());
        verify(thumbnailRenderer, times(1)).render(any(), any(), any());
        ArgumentCaptor<GeneratedProductData> captor = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository).save(captor.capture());
        assertThat(captor.getValue().getDetailHtml()).isEqualTo("EDITED");
        assertThat(captor.getValue().getSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
        assertThat(captor.getValue().getThumbnailUrl()).isEqualTo("thumbnails/generated.jpg");
        assertThat(captor.getValue().getThumbnailSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    @Test
    void clearThumbnail_flipsToAutoFirst_thenReRenders_detailUntouched() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();
        GeneratedProductData override = GeneratedProductData.builder()
                .id(88L).productListing(cell).thumbnailUrl("override.jpg")
                .thumbnailSource(GeneratedContentSource.MANUAL_OVERRIDE)
                .detailHtml("D").source(GeneratedContentSource.AUTO).build();
        // Simulate persistence: the save(AUTO) flip happens before regenerateAssets re-reads, so the second
        // findByProductListingId returns an AUTO row (guard bypassed → re-render).
        GeneratedProductData afterFlip = override.toBuilder()
                .thumbnailSource(GeneratedContentSource.AUTO).build();

        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        given(generatedProductDataRepository.findByProductListingId(CELL_ID))
                .willReturn(Optional.of(override), Optional.of(afterFlip));
        given(productListingOptionRepository.findByProductListingId(CELL_ID)).willReturn(List.of(option()));
        given(productListingProductRepository.findByProductListingOptionId(OPTION_ID))
                .willReturn(List.of(ProductListingProduct.builder().product(product()).quantity(1).build()));
        given(productImageLoader.load(any())).willReturn(new byte[]{7});
        given(channelTemplateResolver.resolveThumbnail(any())).willReturn(template());
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{1, 2, 3});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/rerendered.jpg");
        given(priceCalculator.calculatePrice(any(), any(), any())).willReturn(new BigDecimal("10670"));
        given(detailContentGenerator.generate(any())).willReturn("<p>운동화</p>");
        given(generatedProductDataRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.clearThumbnail(CELL_ID);

        // Two saves: (1) the AUTO flip, (2) the regenerate upsert. The re-render proves the guard was bypassed.
        verify(thumbnailRenderer, times(1)).render(any(), any(), any());
        verify(imageStorageService, times(1)).uploadBytes(any(), anyString(), anyString(), anyString());
        ArgumentCaptor<GeneratedProductData> captor = ArgumentCaptor.forClass(GeneratedProductData.class);
        verify(generatedProductDataRepository, times(2)).save(captor.capture());
        // First save = the flip to AUTO (still the old url, source untouched).
        assertThat(captor.getAllValues().get(0).getThumbnailSource()).isEqualTo(GeneratedContentSource.AUTO);
        // Final save = the AUTO re-render with a fresh url; detail (source) is untouched.
        GeneratedProductData last = captor.getAllValues().get(1);
        assertThat(last.getThumbnailUrl()).isEqualTo("thumbnails/rerendered.jpg");
        assertThat(last.getThumbnailSource()).isEqualTo(GeneratedContentSource.AUTO);
        assertThat(last.getSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    // ---- resolved detail template (29) ----

    @Test
    void resolveDetailTemplate_mapsResolverResultToResponse() {
        ProductListing cell = ProductListing.builder().id(CELL_ID).platform("COUPANG").name("셀").build();
        DetailTemplate resolved = DetailTemplate.builder().id(9L).name("판매채널 상세").active(true).isDefault(false)
                .blocks(List.of(
                        DetailBlock.builder().type("text").bind("brandName").build(),
                        DetailBlock.builder().type("imageZone").bind("product_photos").build()))
                .build();
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));
        given(channelTemplateResolver.resolveDetail(cell)).willReturn(resolved);

        var res = service.resolveDetailTemplate(CELL_ID);

        // The template the resolver returned is mapped through verbatim (id + blocks preserved).
        assertThat(res.getId()).isEqualTo(9L);
        assertThat(res.getName()).isEqualTo("판매채널 상세");
        assertThat(res.getBlocks()).hasSize(2);
        assertThat(res.getIsDefault()).isFalse();
    }

    @Test
    void resolveDetailTemplate_cellAbsent_throws404() {
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveDetailTemplate(CELL_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
