package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.ProductThumbnail;
import com.pms.domain.Seller;
import com.pms.domain.TemplateField;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.response.ProductThumbnailResponse;
import com.pms.repository.ProductRepository;
import com.pms.repository.ProductThumbnailRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * ProductThumbnailService business logic: field-driven text bindings (override > default, blank skip,
 * product text NOT referenced), the regeneration upsert (update-not-insert), and manual override
 * (source=MANUAL_OVERRIDE, renderer bypassed).
 */
@ExtendWith(MockitoExtension.class)
class ProductThumbnailServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private ThumbnailTemplateRepository templateRepository;
    @Mock private ProductThumbnailRepository thumbnailRepository;
    @Mock private ThumbnailRenderer thumbnailRenderer;
    @Mock private ProductImageLoader productImageLoader;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private ProductThumbnailServiceImpl service;

    private static final Long PRODUCT_ID = 10L;
    private static final Long SELLER_ID = 3L;

    private Product product() {
        return Product.builder().id(PRODUCT_ID).brand("나이키").productName("운동화").build();
    }

    private Seller seller() {
        return Seller.builder().id(SELLER_ID).sellerName("행복상회").build();
    }

    private TemplateField field(String key, String defaultValue) {
        return TemplateField.builder().key(key).label(key).defaultValue(defaultValue).build();
    }

    private ThumbnailTemplate template(Long id, TemplateField... fields) {
        return ThumbnailTemplate.builder()
                .id(id).name("t").canvasWidth(300).canvasHeight(300)
                .fields(List.of(fields)).active(true).isDefault(true).build();
    }

    /** Common stubs for a successful generate → render → upload → save (new row). */
    private void stubGenerate(ThumbnailTemplate template) {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(templateRepository.findByIsDefaultTrueAndActiveTrue()).willReturn(Optional.of(template));
        given(productImageLoader.load(any())).willReturn(new byte[]{1, 2, 3});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{9});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/x.jpg");
        given(thumbnailRepository.findByProductIdAndSellerId(PRODUCT_ID, SELLER_ID))
                .willReturn(Optional.empty());
        given(thumbnailRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedTextBindings() {
        ArgumentCaptor<Map<String, String>> textCaptor = ArgumentCaptor.forClass(Map.class);
        verify(thumbnailRenderer).render(any(), textCaptor.capture(), any());
        return textCaptor.getValue();
    }

    @Test
    void generate_usesFieldDefault_whenNoOverride() {
        stubGenerate(template(5L, field("promo", "세일중")));

        service.generate(PRODUCT_ID, SELLER_ID, Map.of());

        assertThat(capturedTextBindings()).containsEntry("promo", "세일중");
    }

    @Test
    void generate_overrideBeatsDefault() {
        stubGenerate(template(5L, field("promo", "세일중")));

        service.generate(PRODUCT_ID, SELLER_ID, Map.of("promo", "특가"));

        assertThat(capturedTextBindings()).containsEntry("promo", "특가");
    }

    @Test
    void generate_bindsFieldValues_notProductText() {
        // Reserved fields with blank defaults; values come ONLY from fieldValues, never product.getBrand().
        stubGenerate(template(5L, field("brandName", ""), field("productName", "")));

        service.generate(PRODUCT_ID, SELLER_ID,
                Map.of("brandName", "직접브랜드", "productName", "직접상품"));

        Map<String, String> bindings = capturedTextBindings();
        assertThat(bindings).containsEntry("brandName", "직접브랜드"); // NOT product brand "나이키"
        assertThat(bindings).containsEntry("productName", "직접상품");
        assertThat(bindings).doesNotContainValue("나이키");
        // image binding still loaded from the product (unchanged behavior)
        verify(productImageLoader).load(any());
    }

    @Test
    void generate_blankReservedField_skippedFromBindings() {
        // brandName reserved (blank default) with no override → key omitted → renderer skips the element.
        stubGenerate(template(5L, field("brandName", "")));

        service.generate(PRODUCT_ID, SELLER_ID, Map.of());

        assertThat(capturedTextBindings()).doesNotContainKey("brandName");
    }

    @Test
    void generate_existing_updatesInsteadOfInsert() {
        ProductThumbnail existing = ProductThumbnail.builder()
                .id(99L).productId(PRODUCT_ID).sellerId(SELLER_ID)
                .imageUrl("thumbnails/old.jpg").templateId(5L)
                .source(ProductThumbnail.Source.GENERATED).build();
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(templateRepository.findByIsDefaultTrueAndActiveTrue())
                .willReturn(Optional.of(template(5L, field("productName", ""))));
        given(productImageLoader.load(any())).willReturn(new byte[]{1});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{9});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/new.jpg");
        given(thumbnailRepository.findByProductIdAndSellerId(PRODUCT_ID, SELLER_ID))
                .willReturn(Optional.of(existing));
        given(thumbnailRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.generate(PRODUCT_ID, SELLER_ID, Map.of("productName", "운동화"));

        ArgumentCaptor<ProductThumbnail> saveCaptor = ArgumentCaptor.forClass(ProductThumbnail.class);
        verify(thumbnailRepository).save(saveCaptor.capture());
        ProductThumbnail saved = saveCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(99L);                  // same row, not a new insert
        assertThat(saved.getImageUrl()).isEqualTo("thumbnails/new.jpg");
        // superseded storage object cleaned up best-effort
        verify(imageStorageService).deleteImage("thumbnails/old.jpg");
    }

    @Test
    void generate_usesDefaultTemplate_regardlessOfSeller() {
        stubGenerate(template(7L, field("productName", "")));

        service.generate(PRODUCT_ID, SELLER_ID, Map.of());

        ArgumentCaptor<ThumbnailTemplate> tplCaptor = ArgumentCaptor.forClass(ThumbnailTemplate.class);
        verify(thumbnailRenderer).render(tplCaptor.capture(), any(), any());
        assertThat(tplCaptor.getValue().getId()).isEqualTo(7L);    // the tenant default (seller ignored)
    }

    @Test
    void generate_noDefaultTemplate_throws() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(templateRepository.findByIsDefaultTrueAndActiveTrue()).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(PRODUCT_ID, SELLER_ID, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(thumbnailRenderer, never()).render(any(), any(), any());
    }

    @Test
    void override_setsManualSource() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(imageStorageService.uploadBytes(any(), eq("thumbnails"), anyString(), eq("image/jpeg")))
                .willReturn("thumbnails/manual.jpg");
        given(thumbnailRepository.findByProductIdAndSellerId(PRODUCT_ID, SELLER_ID))
                .willReturn(Optional.empty());
        given(thumbnailRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.override(PRODUCT_ID, SELLER_ID, file);

        verify(thumbnailRenderer, never()).render(any(), any(), any());
        ArgumentCaptor<ProductThumbnail> saveCaptor = ArgumentCaptor.forClass(ProductThumbnail.class);
        verify(thumbnailRepository).save(saveCaptor.capture());
        ProductThumbnail saved = saveCaptor.getValue();
        assertThat(saved.getSource()).isEqualTo(ProductThumbnail.Source.MANUAL_OVERRIDE);
        assertThat(saved.getTemplateId()).isNull();
    }
}
