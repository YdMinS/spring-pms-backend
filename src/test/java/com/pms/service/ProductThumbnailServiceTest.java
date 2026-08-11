package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.ProductThumbnail;
import com.pms.domain.Seller;
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
 * ProductThumbnailService business logic: template resolution, text bindings, and the regeneration
 * upsert (update-not-insert) vs manual override (source=MANUAL_OVERRIDE, renderer bypassed).
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
        return Product.builder().id(PRODUCT_ID).brand("나이키").productName("운동화").name("운동화").build();
    }

    private Seller seller() {
        return Seller.builder().id(SELLER_ID).sellerName("행복상회").build();
    }

    private ThumbnailTemplate template(Long id, Long sellerId) {
        return ThumbnailTemplate.builder()
                .id(id).sellerId(sellerId).name("t").canvasWidth(300).canvasHeight(300).active(true).build();
    }

    @Test
    void generate_newThumbnail_rendersUploadsAndSaves() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(templateRepository.findBySellerIdAndActiveTrue(SELLER_ID))
                .willReturn(List.of(template(5L, SELLER_ID)));
        given(productImageLoader.load(any())).willReturn(new byte[]{1, 2, 3});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{9});
        given(imageStorageService.uploadBytes(any(), eq("thumbnails"), anyString(), eq("image/jpeg")))
                .willReturn("thumbnails/thumb_10_3_1.jpg");
        given(thumbnailRepository.findByProductIdAndSellerId(PRODUCT_ID, SELLER_ID))
                .willReturn(Optional.empty());
        given(thumbnailRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ProductThumbnailResponse response = service.generate(PRODUCT_ID, SELLER_ID);

        // text bindings carry brand + product name
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> textCaptor = ArgumentCaptor.forClass(Map.class);
        verify(thumbnailRenderer).render(any(), textCaptor.capture(), any());
        assertThat(textCaptor.getValue())
                .containsEntry("brandName", "나이키")
                .containsEntry("productName", "운동화");

        verify(imageStorageService).uploadBytes(any(), eq("thumbnails"), anyString(), eq("image/jpeg"));

        ArgumentCaptor<ProductThumbnail> saveCaptor = ArgumentCaptor.forClass(ProductThumbnail.class);
        verify(thumbnailRepository).save(saveCaptor.capture());
        ProductThumbnail saved = saveCaptor.getValue();
        assertThat(saved.getSource()).isEqualTo(ProductThumbnail.Source.GENERATED);
        assertThat(saved.getTemplateId()).isEqualTo(5L);
        assertThat(saved.getImageUrl()).isEqualTo("thumbnails/thumb_10_3_1.jpg");
        assertThat(response.getSellerName()).isEqualTo("행복상회");
    }

    @Test
    void generate_existing_updatesInsteadOfInsert() {
        ProductThumbnail existing = ProductThumbnail.builder()
                .id(99L).productId(PRODUCT_ID).sellerId(SELLER_ID)
                .imageUrl("thumbnails/old.jpg").templateId(5L)
                .source(ProductThumbnail.Source.GENERATED).build();
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(templateRepository.findBySellerIdAndActiveTrue(SELLER_ID))
                .willReturn(List.of(template(5L, SELLER_ID)));
        given(productImageLoader.load(any())).willReturn(new byte[]{1});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{9});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/new.jpg");
        given(thumbnailRepository.findByProductIdAndSellerId(PRODUCT_ID, SELLER_ID))
                .willReturn(Optional.of(existing));
        given(thumbnailRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.generate(PRODUCT_ID, SELLER_ID);

        ArgumentCaptor<ProductThumbnail> saveCaptor = ArgumentCaptor.forClass(ProductThumbnail.class);
        verify(thumbnailRepository).save(saveCaptor.capture());
        ProductThumbnail saved = saveCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(99L);                  // same row, not a new insert
        assertThat(saved.getImageUrl()).isEqualTo("thumbnails/new.jpg");
        // superseded storage object cleaned up best-effort
        verify(imageStorageService).deleteImage("thumbnails/old.jpg");
    }

    @Test
    void generate_sellerTemplateMissing_usesTenantDefault() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(templateRepository.findBySellerIdAndActiveTrue(SELLER_ID)).willReturn(List.of());
        given(templateRepository.findBySellerIdIsNullAndActiveTrue())
                .willReturn(List.of(template(7L, null)));
        given(productImageLoader.load(any())).willReturn(new byte[]{1});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{9});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/x.jpg");
        given(thumbnailRepository.findByProductIdAndSellerId(PRODUCT_ID, SELLER_ID))
                .willReturn(Optional.empty());
        given(thumbnailRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.generate(PRODUCT_ID, SELLER_ID);

        ArgumentCaptor<ThumbnailTemplate> tplCaptor = ArgumentCaptor.forClass(ThumbnailTemplate.class);
        verify(thumbnailRenderer).render(tplCaptor.capture(), any(), any());
        assertThat(tplCaptor.getValue().getId()).isEqualTo(7L);    // tenant-default template used
    }

    @Test
    void generate_noTemplate_throws() {
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(templateRepository.findBySellerIdAndActiveTrue(SELLER_ID)).willReturn(List.of());
        given(templateRepository.findBySellerIdIsNullAndActiveTrue()).willReturn(List.of());

        assertThatThrownBy(() -> service.generate(PRODUCT_ID, SELLER_ID))
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
