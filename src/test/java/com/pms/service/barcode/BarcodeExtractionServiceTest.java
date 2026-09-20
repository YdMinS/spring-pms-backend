package com.pms.service.barcode;

import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.dto.request.BarcodeExtractionRequest;
import com.pms.dto.response.BarcodeExtractionResult;
import com.pms.dto.response.BarcodeExtractionStatus;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import com.pms.service.ProductImageLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link BarcodeExtractionServiceImpl} 단위 테스트 (FEATURE_2609_65).
 *
 * <p>틀릴 수 있는 곳 다섯을 덮는다: ① 값이 있는데 덮어쓴다(D7) ② 중복을 저장한다(D8)
 * ③ 첫 성공 뒤에도 계속 읽는다(D5) ④ {@code READ_FAILED} 를 {@code NOT_FOUND} 로 뭉갠다(D12)
 * ⑤ 한 물품의 실패가 성공분을 되돌린다(D9).</p>
 */
@ExtendWith(MockitoExtension.class)
class BarcodeExtractionServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductImageRepository productImageRepository;
    @Mock private ProductImageLoader productImageLoader;
    @Mock private BarcodeImageDecoder barcodeImageDecoder;

    @InjectMocks private BarcodeExtractionServiceImpl service;

    private static final String BARCODE = "8801234567893";

    private Product product(Long id, String barcodeId) {
        return Product.builder().id(id).productName("물품" + id).barcodeId(barcodeId).build();
    }

    private ProductImage image(Long id, String url) {
        return ProductImage.builder().id(id).imageUrl(url).sortOrder(0).build();
    }

    private BarcodeExtractionRequest request(Long... ids) {
        return new BarcodeExtractionRequest(List.of(ids), null);
    }

    @Test
    void extract_existingBarcode_skipsWithoutReadingImages() {
        Product product = product(1L, "9999999999999");
        given(productRepository.findScopedById(1L)).willReturn(Optional.of(product));

        BarcodeExtractionResult result = service.extract(request(1L));

        assertThat(result.items().get(0).status()).isEqualTo(BarcodeExtractionStatus.SKIPPED_EXISTING);
        assertThat(result.extracted()).isZero();
        verify(productImageRepository, never()).findByProductIdOrderBySortOrderAsc(any());
        verify(productRepository, never()).save(any());
    }

    @Test
    void extract_duplicateBarcode_doesNotSave() {
        Product product = product(1L, null);
        given(productRepository.findScopedById(1L)).willReturn(Optional.of(product));
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image(11L, "http://s3/a.png")));
        given(productImageLoader.loadUrl("http://s3/a.png")).willReturn(new byte[]{1});
        given(barcodeImageDecoder.decode(any())).willReturn(Optional.of(new DecodedBarcode(BARCODE, "EAN_13")));
        given(productRepository.findByBarcodeId(BARCODE)).willReturn(Optional.of(product(2L, BARCODE)));

        BarcodeExtractionResult result = service.extract(request(1L));

        assertThat(result.items().get(0).status()).isEqualTo(BarcodeExtractionStatus.DUPLICATE);
        assertThat(result.items().get(0).barcode()).isEqualTo(BARCODE);
        assertThat(result.extracted()).isZero();
        verify(productRepository, never()).save(any());
    }

    @Test
    void extract_stopsAtFirstHit() {
        Product product = product(1L, null);
        given(productRepository.findScopedById(1L)).willReturn(Optional.of(product));
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(1L)).willReturn(List.of(
                image(11L, "http://s3/a.png"), image(12L, "http://s3/b.png"), image(13L, "http://s3/c.png")));
        given(productImageLoader.loadUrl(any())).willReturn(new byte[]{1});
        given(barcodeImageDecoder.decode(any()))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(new DecodedBarcode(BARCODE, "EAN_13")));
        given(productRepository.findByBarcodeId(BARCODE)).willReturn(Optional.empty());

        BarcodeExtractionResult result = service.extract(request(1L));

        // 🔴 세 번째 사진은 읽지 않는다.
        verify(productImageLoader, times(2)).loadUrl(any());
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertThat(saved.getValue().getBarcodeId()).isEqualTo(BARCODE);
        assertThat(result.items().get(0).status()).isEqualTo(BarcodeExtractionStatus.EXTRACTED);
        assertThat(result.items().get(0).format()).isEqualTo("EAN_13");
        assertThat(result.items().get(0).productImageId()).isEqualTo(12L);
        assertThat(result.extracted()).isEqualTo(1);
    }

    @Test
    void extract_loadFailure_isReadFailedNotNotFound() {
        given(productRepository.findScopedById(1L)).willReturn(Optional.of(product(1L, null)));
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image(11L, "http://s3/a.png")));
        willThrow(new IllegalArgumentException("상품 이미지를 불러올 수 없습니다"))
                .given(productImageLoader).loadUrl("http://s3/a.png");

        BarcodeExtractionResult result = service.extract(request(1L));

        assertThat(result.items().get(0).status()).isEqualTo(BarcodeExtractionStatus.READ_FAILED);
        verify(productRepository, never()).save(any());
    }

    @Test
    void extract_oneFailureKeepsOtherSave() {
        given(productRepository.findScopedById(1L)).willReturn(Optional.of(product(1L, null)));
        given(productRepository.findScopedById(2L)).willReturn(Optional.of(product(2L, null)));
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image(11L, "http://s3/a.png")));
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(2L))
                .willReturn(List.of(image(21L, "http://s3/b.png")));
        willThrow(new IllegalArgumentException("상품 이미지를 불러올 수 없습니다"))
                .given(productImageLoader).loadUrl("http://s3/a.png");
        given(productImageLoader.loadUrl("http://s3/b.png")).willReturn(new byte[]{1});
        given(barcodeImageDecoder.decode(any())).willReturn(Optional.of(new DecodedBarcode(BARCODE, "EAN_13")));
        given(productRepository.findByBarcodeId(BARCODE)).willReturn(Optional.empty());

        BarcodeExtractionResult result = service.extract(request(1L, 2L));

        assertThat(result.items().get(0).status()).isEqualTo(BarcodeExtractionStatus.READ_FAILED);
        assertThat(result.items().get(1).status()).isEqualTo(BarcodeExtractionStatus.EXTRACTED);
        assertThat(result.extracted()).isEqualTo(1);
        verify(productRepository, times(1)).save(any());
    }
}
