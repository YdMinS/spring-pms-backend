package com.pms.service;

import com.pms.config.ImageStorageProperties;
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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
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
    // 🔴 2609_67: Impl 이 새로 주입받는 두 협력자. 목을 안 달면 @InjectMocks 가 null 을 넣어 NPE 로 죽는다.
    @Mock private ProductImageLoader productImageLoader;
    @Mock private ImageStorageProperties imageStorageProperties;

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

    // ------------------------------------------------------------------ addImagesFromUrls (2609_67)

    /** JPEG 매직바이트({@code FF D8 FF}) — 형식 판정은 응답 헤더가 아니라 이 바이트가 한다. */
    private static byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x11, 0x22};
    }

    @Test
    void addImagesFromUrlsAppendsAfterExisting() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        // 삭제가 남긴 구멍(0, 3) — size() 로 잡으면 기존 sortOrder 와 충돌한다.
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID))
                .willReturn(List.of(image(1L, 0), image(2L, 3)));
        given(productImageLoader.loadUrl("https://image1.coupangcdn.com/a.jpg")).willReturn(jpegBytes());
        given(imageStorageProperties.getMaxFileSize()).willReturn(20971520L);
        given(imageStorageService.uploadBytes(any(), eq("products"), any(), eq("image/jpeg")))
                .willReturn("s3/copied.jpg");
        given(imageRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.addImagesFromUrls(PRODUCT_ID, List.of("https://image1.coupangcdn.com/a.jpg"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> saved = ArgumentCaptor.forClass(List.class);
        verify(imageRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getSortOrder()).isEqualTo(4);          // max+1
        assertThat(saved.getValue().get(0).getImageUrl()).isEqualTo("s3/copied.jpg"); // 우리 저장소 값
    }

    /**
     * 🔴 SSRF 가드: 허용 호스트가 아니면 <b>내려받기 전에</b> 400 이다(외부 호출 0회).
     * 저장소 호스트를 스텁하지 않았으므로(=local 저장소) 쿠팡 CDN 만 남는다.
     */
    @Test
    void addImagesFromUrlsRejectsForeignHost() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));

        assertThatThrownBy(() -> service.addImagesFromUrls(PRODUCT_ID, List.of("https://evil.com/a.jpg")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productImageLoader, never()).loadUrl(any());
        verify(imageRepository, never()).saveAll(any());
    }

    /**
     * 🔴 우리가 올려서 쿠팡에 보낸 사진도 가져올 수 있어야 한다 — 마켓 응답에는 우리 저장소 주소가 섞여 있다
     * (대표 사진의 {@code vendorPath}, 우리가 만든 상세 HTML 의 {@code <img src>}).
     */
    @Test
    void addImagesFromUrlsAcceptsOwnStorageHost() {
        String url = "https://oclyx-product-images-dev.s3.ap-northeast-2.amazonaws.com/tenants/1/products/a.jpg";
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageStorageProperties.resolvePublicImageHost())
                .willReturn("oclyx-product-images-dev.s3.ap-northeast-2.amazonaws.com");
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of());
        given(productImageLoader.loadUrl(url)).willReturn(jpegBytes());
        given(imageStorageProperties.getMaxFileSize()).willReturn(20971520L);
        given(imageStorageService.uploadBytes(any(), eq("products"), any(), eq("image/jpeg")))
                .willReturn("s3/copied.jpg");
        given(imageRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.addImagesFromUrls(PRODUCT_ID, List.of(url));

        verify(imageRepository).saveAll(any());
    }

    /**
     * 🔴 http 로 온 마켓 사진도 받되, <b>실제로 치는 주소는 https</b> 다.
     * 브라우저가 https 화면에서 http 이미지를 자동으로 올려 보여주므로 사용자 눈엔 멀쩡한 사진인데,
     * 스킴만 보고 막으면 "보이는데 못 가져오는" 사진이 된다. 클립보드에 저장된 옛 주소도 같은 경로를 탄다.
     */
    @Test
    void addImagesFromUrlsUpgradesHttpToHttps() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of());
        given(productImageLoader.loadUrl("https://image1.coupangcdn.com/a.jpg")).willReturn(jpegBytes());
        given(imageStorageProperties.getMaxFileSize()).willReturn(20971520L);
        given(imageStorageService.uploadBytes(any(), eq("products"), any(), eq("image/jpeg")))
                .willReturn("s3/copied.jpg");
        given(imageRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.addImagesFromUrls(PRODUCT_ID, List.of("http://image1.coupangcdn.com/a.jpg"));

        // 🔴 평문 요청을 대신 보내주는 것이 아니라 아예 보내지 않는다 — 가져오는 주소는 https 하나뿐이다.
        verify(productImageLoader).loadUrl("https://image1.coupangcdn.com/a.jpg");
        verify(productImageLoader, never()).loadUrl("http://image1.coupangcdn.com/a.jpg");
    }

    /** 스킴 승격은 <b>호스트를 넓히지 않는다</b> — http 라도 허용 밖 호스트는 그대로 400. */
    @Test
    void addImagesFromUrlsRejectsHttpOnForeignHost() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));

        assertThatThrownBy(() -> service.addImagesFromUrls(PRODUCT_ID, List.of("http://evil.com/a.jpg")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productImageLoader, never()).loadUrl(any());
    }

    /**
     * 🔴 우리 저장소 호스트는 <b>완전일치</b>다 — 접미사로 비교하면 같은 도메인의 남의 버킷이 전부 통과한다.
     */
    @Test
    void addImagesFromUrlsRejectsOtherBucketOnSameDomain() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageStorageProperties.resolvePublicImageHost())
                .willReturn("oclyx-product-images-dev.s3.ap-northeast-2.amazonaws.com");

        assertThatThrownBy(() -> service.addImagesFromUrls(
                PRODUCT_ID, List.of("https://evil-bucket.s3.ap-northeast-2.amazonaws.com/a.jpg")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productImageLoader, never()).loadUrl(any());
        verify(imageRepository, never()).saveAll(any());
    }

    /** 🔴 형식은 매직바이트로 정한다 — 마켓이 무엇을 주든 이미지가 아니면 저장하지 않는다. */
    @Test
    void addImagesFromUrlsRejectsNonImageBytes() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of());
        given(productImageLoader.loadUrl("https://image1.coupangcdn.com/a.jpg"))
                .willReturn("not an image".getBytes());

        assertThatThrownBy(() ->
                service.addImagesFromUrls(PRODUCT_ID, List.of("https://image1.coupangcdn.com/a.jpg")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미지 파일이 아닙니다");
        verify(imageStorageService, never()).uploadBytes(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ copyImages (62)

    @Test
    void copyImages_sharesSourceUrlWithoutUpload() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID))
                .willReturn(List.of(image(1L, 0), image(2L, 1)));
        Product source = Product.builder().id(77L).productName("src").active(true).build();
        given(imageRepository.findById(30L)).willReturn(Optional.of(
                ProductImage.builder().id(30L).product(source).sortOrder(0).imageUrl("s3/a.jpg").build()));
        given(productRepository.findScopedById(77L)).willReturn(Optional.of(source));
        given(imageRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.copyImages(PRODUCT_ID, List.of(30L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> saved = ArgumentCaptor.forClass(List.class);
        verify(imageRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getImageUrl()).isEqualTo("s3/a.jpg"); // url shared as-is
        assertThat(saved.getValue().get(0).getSortOrder()).isEqualTo(2);         // appended after 0, 1
        verify(imageStorageService, never()).uploadImage(any(), any());          // reference copy: no upload
    }

    @Test
    void copyImages_continuesAfterGap() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        // Gallery with a hole (a delete left sortOrder 0, 5) — size() would produce a colliding 2.
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID))
                .willReturn(List.of(image(1L, 0), image(2L, 5)));
        given(imageRepository.findById(30L)).willReturn(Optional.of(image(30L, 0)));
        given(imageRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.copyImages(PRODUCT_ID, List.of(30L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> saved = ArgumentCaptor.forClass(List.class);
        verify(imageRepository).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).getSortOrder()).isEqualTo(6); // max+1
    }

    @Test
    void copyImages_skipsMissingSource() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of());
        given(imageRepository.findById(30L)).willReturn(Optional.empty());       // deleted meanwhile
        given(imageRepository.findById(31L)).willReturn(Optional.of(image(31L, 0)));
        given(imageRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        service.copyImages(PRODUCT_ID, List.of(30L, 31L)); // no exception

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> saved = ArgumentCaptor.forClass(List.class);
        verify(imageRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getImageUrl()).isEqualTo("u31");
    }

    @Test
    void copyImages_allSourcesMissing_400() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of());
        given(imageRepository.findById(30L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.copyImages(PRODUCT_ID, List.of(30L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(imageRepository, never()).saveAll(any());
    }

    @Test
    void copyImages_emptyList_400() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));

        assertThatThrownBy(() -> service.copyImages(PRODUCT_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
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

    // ------------------------------------------------------------------ shared-url physical delete guard (62)

    @Test
    void deleteImage_keepsFileWhenUrlShared() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findById(5L)).willReturn(Optional.of(image(5L, 0)));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of(image(6L, 1)));
        given(imageRepository.countByImageUrl("u5")).willReturn(1L); // another product copied this url

        service.deleteImage(PRODUCT_ID, 5L);

        verify(imageStorageService, never()).deleteImage(any());
    }

    @Test
    void deleteImage_keepsFileWhenRepresentativeShared() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findById(5L)).willReturn(Optional.of(image(5L, 0)));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of(image(6L, 1)));
        given(imageRepository.countByImageUrl("u5")).willReturn(0L);
        // No row left, but another product still shows this url as its representative (empty-gallery rule).
        given(productRepository.existsByImageUrl("u5")).willReturn(true);

        service.deleteImage(PRODUCT_ID, 5L);

        verify(imageStorageService, never()).deleteImage(any());
    }

    @Test
    void deleteImage_removesFileWhenUrlUnique() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findById(5L)).willReturn(Optional.of(image(5L, 0)));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of(image(6L, 1)));
        given(imageRepository.countByImageUrl("u5")).willReturn(0L);
        given(productRepository.existsByImageUrl("u5")).willReturn(false);

        service.deleteImage(PRODUCT_ID, 5L);

        verify(imageStorageService).deleteImage("u5");
        // The representative must move to the next image BEFORE the guard counts, otherwise this product
        // would count itself and the file would never be freed.
        InOrder order = inOrder(productRepository);
        order.verify(productRepository).save(any(Product.class));
        order.verify(productRepository).existsByImageUrl("u5");
    }

    @Test
    void replaceImage_keepsOldFileWhenUrlShared() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findById(5L)).willReturn(Optional.of(image(5L, 0)));
        given(imageStorageService.uploadImage(any(), eq(PRODUCT_ID))).willReturn("new-url");
        given(imageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of(image(6L, 1)));
        given(imageRepository.countByImageUrl("u5")).willReturn(1L); // a copied row still uses the old file

        service.replaceImage(PRODUCT_ID, 5L, mockFile());

        verify(imageStorageService, never()).deleteImage(any());
    }

    @Test
    void replaceImage_deletesOldFileWhenUnique() {
        given(productRepository.findScopedById(PRODUCT_ID)).willReturn(Optional.of(product()));
        given(imageRepository.findById(5L)).willReturn(Optional.of(image(5L, 0)));
        given(imageStorageService.uploadImage(any(), eq(PRODUCT_ID))).willReturn("new-url");
        given(imageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(imageRepository.findByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of(image(6L, 1)));
        given(imageRepository.countByImageUrl("u5")).willReturn(0L);
        given(productRepository.existsByImageUrl("u5")).willReturn(false);

        service.replaceImage(PRODUCT_ID, 5L, mockFile());

        verify(imageStorageService).deleteImage("u5");
    }
}
