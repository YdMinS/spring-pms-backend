package com.pms.service;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.MasterProduct;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.TemplateField;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private ChannelTemplateResolver channelTemplateResolver;
    @Mock private ThumbnailRenderer thumbnailRenderer;
    @Mock private ProductImageLoader productImageLoader;
    @Mock private ImageStorageService imageStorageService;
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
}
