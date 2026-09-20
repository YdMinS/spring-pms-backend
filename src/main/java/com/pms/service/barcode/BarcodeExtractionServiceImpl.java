package com.pms.service.barcode;

import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.dto.request.BarcodeExtractionRequest;
import com.pms.dto.response.BarcodeExtractionItem;
import com.pms.dto.response.BarcodeExtractionResult;
import com.pms.dto.response.BarcodeExtractionStatus;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import com.pms.service.ProductImageLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 물품 사진에서 바코드를 읽어 {@code products.barcode_id} 를 채운다 (FEATURE_2609_65 / PLAN D5 · D7~D9 · D12).
 *
 * <p><b>결과 6종의 뜻</b> — 화면이 이 값을 그대로 문장으로 보여준다:</p>
 * <ul>
 *   <li>{@code EXTRACTED} — 읽어서 저장했다.</li>
 *   <li>{@code NOT_FOUND} — 사진은 읽었는데 바코드가 없다. <b>정상적인 실패</b>다(연출컷엔 대개 없다).</li>
 *   <li>{@code NO_IMAGE} — 볼 사진이 없다.</li>
 *   <li>{@code READ_FAILED} — 사진을 가져오지 못했다(S3·네트워크·깨진 파일). <b>사람이 볼 일</b>이다.</li>
 *   <li>{@code DUPLICATE} — 읽었지만 다른 물품이 쓰는 값이라 저장하지 않았다.</li>
 *   <li>{@code SKIPPED_EXISTING} — 이미 바코드가 있다({@code overwrite=false}).</li>
 * </ul>
 * 🔴 {@code NOT_FOUND} 와 {@code READ_FAILED} 를 합치지 말 것 — 합치면 S3 장애를 "바코드 없음"으로 읽는다(D12).
 *
 * <p><b>🔴 {@code @Transactional} 이 없는 것은 빠뜨린 것이 아니다</b>(D9). 루프 안에 S3 HTTP GET 이 있어서
 * 한 트랜잭션으로 묶으면 ① DB 커넥션을 네트워크 대기 시간만큼 붙잡고 ② 한 물품의 실패가 앞서 성공한
 * 물품의 저장을 되돌린다. 20개 중 3개가 성공했으면 그 3개는 저장돼야 한다. 저장은 물품별
 * {@code save()} 한 번으로 각각 커밋한다(Spring Data 의 기본 트랜잭션).</p>
 *
 * <p><b>저장값을 가공하지 않는다</b>(D3). 피킹 스캔 조회가 {@code findByBarcodeId(value.trim())}
 * <b>정확일치</b>({@code PackingServiceImpl}) 라서, 하이픈 삽입·0 패딩·앞자리 보정을 하는 순간 스캐너 출력과
 * 영원히 어긋난다. 디코더 원문을 그대로 넣는다.</p>
 *
 * <p>⚠️ {@code ProductImageLoader.loadUrl} 에는 시간 제한이 없다({@code URL.openStream()}) — 프록시가 60초에
 * 끊어도 이 루프는 계속 돌고, 트랜잭션이 없으니 저장도 계속된다. 그래서 물품별 결과를 debug 로 한 줄씩
 * 남긴다: 504 뒤에 "무엇이 저장됐는지" 를 아는 유일한 수단이다. 공용 로더에 타임아웃을 넣는 것은 이 기능
 * 밖의 일이다(D14) — 한 요청의 크기(물품 50 · 물품당 사진 10)로만 막는다.</p>
 *
 * <p>File: {@code service/barcode/BarcodeExtractionServiceImpl.java}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BarcodeExtractionServiceImpl implements BarcodeExtractionService {

    /** 한 물품당 훑어볼 사진 수 상한 (그 뒤는 나열용 사진이라 바코드 확률이 낮고 시간만 먹는다, D5). */
    private static final int MAX_IMAGES_PER_PRODUCT = 10;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductImageLoader productImageLoader;
    private final BarcodeImageDecoder barcodeImageDecoder;

    @Override
    public BarcodeExtractionResult extract(BarcodeExtractionRequest request) {
        // 🔴 사진을 한 장이라도 읽기 전에 전체 id 를 해석한다 — 중간에 404 로 끊기면
        // "몇 개는 저장됐는데 응답은 404" 가 된다. 테넌트 격리는 findScopedById 가 소유한다.
        List<Product> products = new ArrayList<>();
        for (Long id : request.productIds()) {
            products.add(productRepository.findScopedById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Product", id)));
        }

        boolean overwrite = request.overwriteOrFalse();
        List<BarcodeExtractionItem> items = new ArrayList<>();
        int extracted = 0;
        for (Product product : products) {
            BarcodeExtractionItem item = extractOne(product, overwrite);
            log.debug("barcode extraction: productId={} status={}", item.productId(), item.status());
            if (item.status() == BarcodeExtractionStatus.EXTRACTED) {
                extracted++;
            }
            items.add(item);
        }

        log.info("barcode extraction: requested={} extracted={}", items.size(), extracted);
        return new BarcodeExtractionResult(items.size(), extracted, items);
    }

    private BarcodeExtractionItem extractOne(Product product, boolean overwrite) {
        if (!overwrite && product.getBarcodeId() != null && !product.getBarcodeId().isBlank()) {
            // 사진을 아예 읽지 않는다 (D7).
            return item(product, BarcodeExtractionStatus.SKIPPED_EXISTING, null, null, null);
        }

        List<Candidate> candidates = candidates(product);
        if (candidates.isEmpty()) {
            return item(product, BarcodeExtractionStatus.NO_IMAGE, null, null, null);
        }

        boolean readFailed = false;
        for (Candidate candidate : candidates) {
            Optional<DecodedBarcode> decoded;
            try {
                byte[] bytes = productImageLoader.loadUrl(candidate.imageUrl());
                decoded = barcodeImageDecoder.decode(bytes);
            } catch (RuntimeException e) {
                // 🔴 밖으로 흘리면 GlobalExceptionHandler 가 잡아 요청 전체가 400 이 된다.
                log.warn("barcode image read failed: productId={} url={}", product.getId(), candidate.imageUrl(), e);
                readFailed = true;
                continue;
            }
            if (decoded.isPresent()) {
                // 🔴 첫 성공에서 멈춘다 — 남은 사진을 더 읽지 않는다 (D5).
                return store(product, decoded.get(), candidate.productImageId());
            }
        }
        return item(product, readFailed ? BarcodeExtractionStatus.READ_FAILED : BarcodeExtractionStatus.NOT_FOUND,
                null, null, null);
    }

    /**
     * 저장 판정 (D8). 🔴 {@code existsByBarcodeId} 가 아니라 {@code findByBarcodeId} + id 비교를 쓴다 —
     * {@code overwrite=true} 로 같은 값을 다시 읽은 경우 {@code exists} 는 자기 자신을 중복으로 오판한다.
     *
     * <p>⚠️ 같은 요청 안의 중복도 여기 걸린다(물품별로 커밋하므로 뒤 물품이 앞 물품의 값을 본다).
     * 클립보드(2609_62)로 사진을 공유한 두 물품이 실제로 이 경로를 탄다.</p>
     */
    private BarcodeExtractionItem store(Product product, DecodedBarcode decoded, Long productImageId) {
        Product owner = productRepository.findByBarcodeId(decoded.text()).orElse(null);
        if (owner != null && !owner.getId().equals(product.getId())) {
            return item(product, BarcodeExtractionStatus.DUPLICATE, decoded.text(), null, productImageId);
        }
        // toBuilder = 불변 갱신 패턴 (ProductServiceImpl.update 와 동일) — 감사 필드·tenantId 를 보존한다.
        productRepository.save(product.toBuilder().barcodeId(decoded.text()).build());
        return item(product, BarcodeExtractionStatus.EXTRACTED, decoded.text(), decoded.format(), productImageId);
    }

    /**
     * 훑어볼 사진 목록 (D5): 갤러리 순서대로, 갤러리가 비었을 때만 레거시 대표 이미지 1장,
     * 물품당 최대 {@value #MAX_IMAGES_PER_PRODUCT} 장.
     */
    private List<Candidate> candidates(Product product) {
        List<ProductImage> images = productImageRepository.findByProductIdOrderBySortOrderAsc(product.getId());
        if (!images.isEmpty()) {
            return images.stream()
                    .limit(MAX_IMAGES_PER_PRODUCT)
                    .map(image -> new Candidate(image.getImageUrl(), image.getId()))
                    .toList();
        }
        if (product.getImageUrl() != null && !product.getImageUrl().isBlank()) {
            return List.of(new Candidate(product.getImageUrl(), null));
        }
        return List.of();
    }

    private BarcodeExtractionItem item(Product product, BarcodeExtractionStatus status,
                                       String barcode, String format, Long productImageId) {
        return new BarcodeExtractionItem(product.getId(), product.getProductName(),
                status, barcode, format, productImageId);
    }

    /** 후보 사진 한 장. {@code productImageId} 는 레거시 대표 이미지면 null 이다. */
    private record Candidate(String imageUrl, Long productImageId) {
    }
}
