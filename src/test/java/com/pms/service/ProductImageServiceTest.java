package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.dto.response.ProductImageResponse;
import com.pms.exception.ImageInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductImageRepository imageRepository;
    @Mock private MasterImageZoneAssignmentRepository assignmentRepository;
    @Mock private MasterProductImageRepository masterProductImageRepository;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private ProductImageServiceImpl service;

    private static final Long PRODUCT_ID = 1L;

    private Product product() {
        return Product.builder().id(PRODUCT_ID).productName("p").active(true).build();
    }

    private ProductImage image(Long id, int sortOrder) {
        return image(id, sortOrder, product());
    }

    private ProductImage image(Long id, int sortOrder, Product owner) {
        return ProductImage.builder().id(id).product(owner).sortOrder(sortOrder).imageUrl("u" + id).build();
    }

    private MultipartFile mockFile() {
        return org.mockito.Mockito.mock(MultipartFile.class);
    }

    // ------------------------------------------------------------------ addImages

    @Test
    void addImages_emptyGallery_appendsWithSortOrderZeroAndOne_syncsRepresentative() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of());
        given(imageStorageService.uploadImage(any(), eq(PRODUCT_ID))).willReturn("up0", "up1");
        given(imageRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.addImages(PRODUCT_ID, List.of(mockFile(), mockFile()));

        // Single saveAll with the two new rows at sortOrder 0, 1.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> saved = ArgumentCaptor.forClass(List.class);
        verify(imageRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue().get(0).getSortOrder()).isZero();
        assertThat(saved.getValue().get(0).getImageUrl()).isEqualTo("up0");
        assertThat(saved.getValue().get(1).getSortOrder()).isEqualTo(1);

        // Representative = first gallery image.
        ArgumentCaptor<Product> rep = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(rep.capture());
        assertThat(rep.getValue().getImageUrl()).isEqualTo("up0");
    }

    @Test
    void addImages_missingProduct_404_neverSaves() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.addImages(PRODUCT_ID, List.of(mockFile())))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(imageRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------ replaceImage

    @Test
    void replaceImage_keepsSameIdReplacesUrl_deletesOldBestEffort() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findById(5L)).willReturn(Optional.of(image(5L, 0)));
        given(imageStorageService.uploadImage(any(), eq(PRODUCT_ID))).willReturn("new-url");
        given(imageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.replaceImage(PRODUCT_ID, 5L, mockFile());

        ArgumentCaptor<ProductImage> c = ArgumentCaptor.forClass(ProductImage.class);
        verify(imageRepository).save(c.capture());
        assertThat(c.getValue().getId()).isEqualTo(5L);           // same id (update-in-place)
        assertThat(c.getValue().getImageUrl()).isEqualTo("new-url");
        verify(imageStorageService).deleteImage("u5");            // old url removed once
    }

    @Test
    void replaceImage_imageOfAnotherProduct_404() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        Product other = Product.builder().id(999L).build();
        given(imageRepository.findById(9L)).willReturn(Optional.of(image(9L, 0, other)));

        assertThatThrownBy(() -> service.replaceImage(PRODUCT_ID, 9L, mockFile()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ reorder

    @Test
    void reorder_setMismatch_400_neverSaves() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID))
                .willReturn(List.of(image(1L, 0), image(2L, 1)));

        assertThatThrownBy(() -> service.reorder(PRODUCT_ID, List.of(1L, 3L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(imageRepository, never()).saveAll(any());
    }

    @Test
    void reorder_match_reassignsSortOrder_syncsRepresentative() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID))
                .willReturn(List.of(image(1L, 0), image(2L, 1)));
        given(imageRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.reorder(PRODUCT_ID, List.of(2L, 1L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> saved = ArgumentCaptor.forClass(List.class);
        verify(imageRepository).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).getId()).isEqualTo(2L);
        assertThat(saved.getValue().get(0).getSortOrder()).isZero();
        assertThat(saved.getValue().get(1).getId()).isEqualTo(1L);
        assertThat(saved.getValue().get(1).getSortOrder()).isEqualTo(1);

        ArgumentCaptor<Product> rep = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(rep.capture());
        assertThat(rep.getValue().getImageUrl()).isEqualTo("u2"); // new first image
    }

    // ------------------------------------------------------------------ deleteImage

    @Test
    void deleteImage_imageOfAnotherProduct_404() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        Product other = Product.builder().id(999L).build();
        given(imageRepository.findById(9L)).willReturn(Optional.of(image(9L, 0, other)));

        assertThatThrownBy(() -> service.deleteImage(PRODUCT_ID, 9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteImage_notLast_deletesRowAndStorage_recomputesRepresentative() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        ProductImage target = image(5L, 0);
        given(imageRepository.findById(5L)).willReturn(Optional.of(target));
        // Gallery still has another image after the delete → physical delete + representative recompute.
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of(image(6L, 1)));

        service.deleteImage(PRODUCT_ID, 5L);

        verify(imageRepository).delete(target);
        verify(imageStorageService).deleteImage("u5");
        ArgumentCaptor<Product> rep = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(rep.capture());
        assertThat(rep.getValue().getImageUrl()).isEqualTo("u6");
    }

    @Test
    void deleteImage_lastImage_skipsStorageDelete_keepsRepresentative() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        ProductImage target = image(5L, 0);
        given(imageRepository.findById(5L)).willReturn(Optional.of(target));
        // Gallery empty after the delete → skip physical delete (representative still points here) + keep rep.
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of());

        service.deleteImage(PRODUCT_ID, 5L);

        verify(imageRepository).delete(target);
        verify(imageStorageService, never()).deleteImage(any());
        verify(productRepository, never()).save(any()); // representative kept (empty-gallery rule)
    }

    // ------------------------------------------------------------------ deleteImage 40 reference guard

    @Test
    void deleteImage_placedReference_409_preservesImage() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findById(5L)).willReturn(Optional.of(image(5L, 0)));
        // A master pool reference of this slot is placed on a zone/cover (DRAFT included) → conflict.
        given(assignmentRepository.existsByImage_ProductImageId(5L)).willReturn(true);

        assertThatThrownBy(() -> service.deleteImage(PRODUCT_ID, 5L))
                .isInstanceOf(ImageInUseException.class);
        verify(imageRepository, never()).delete(any());                       // source image preserved
        verify(masterProductImageRepository, never()).deleteByProductImageId(any());
    }

    @Test
    void deleteImage_unplacedReference_deletes_cleansUpPaletteEntries() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        ProductImage target = image(5L, 0);
        given(imageRepository.findById(5L)).willReturn(Optional.of(target));
        given(assignmentRepository.existsByImage_ProductImageId(5L)).willReturn(false);
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of(image(6L, 1)));

        service.deleteImage(PRODUCT_ID, 5L);

        verify(masterProductImageRepository).deleteByProductImageId(5L); // unmapped reference entries removed
        verify(imageRepository).delete(target);
        verify(imageStorageService).deleteImage("u5");                  // best-effort storage delete
    }
}
