package com.pms.service.cost;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.PurchaseRecord;
import com.pms.dto.response.CostDeviation;
import com.pms.dto.response.PropagateResponse;
import com.pms.dto.response.PropagationApplyResult;
import com.pms.dto.response.PropagationPreview;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.service.listing.MasterPropagationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 원가 파급 — ① 기준가 갱신 조건, ② 대상 산출·확정, 그리고 <b>①과 ②의 분리</b>.
 *
 * <p>🔴 이 클래스가 지키는 가장 중요한 것: 매입 입력이 판매가를 건드리지 않는다는 것(PLAN D4)과
 * 확정 실행이 채널 push 를 부르지 않는다는 것(③).
 */
@ExtendWith(MockitoExtension.class)
class CostPropagationServiceImplTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private MasterPropagationService masterPropagationService;

    @InjectMocks private CostPropagationServiceImpl service;

    // --- fixtures ---

    private static Product product(Long id, BigDecimal price) {
        return Product.builder().id(id).productName("물품" + id).price(price).build();
    }

    private static PurchaseRecord purchase(Product product, BigDecimal unitPrice, int quantity,
                                           boolean reflect, LocalDate purchasedOn) {
        return PurchaseRecord.builder()
                .id(1L)
                .product(product)
                .unitPrice(unitPrice)
                .quantity(quantity)
                .reflectToBasePrice(reflect)
                .purchasedOn(purchasedOn)
                .build();
    }

    private static ProductListing cell(Long id, MasterProduct master, ListingStatus status,
                                       String platformProductId) {
        return ProductListing.builder()
                .id(id).name("셀" + id).status(status)
                .platformProductId(platformProductId).masterProduct(master)
                .build();
    }

    private static ProductListingOption option(Long id, ProductListing cell, GeneratedContentSource source) {
        return ProductListingOption.builder()
                .id(id).productListing(cell).optionName("옵션" + id)
                .sellingPrice(new BigDecimal("6000")).priceSource(source)
                .build();
    }

    /** 그 물품을 쓰는 마스터 1개(옵션 1개). 미리보기가 물품 → 마스터로 거슬러 오르는 경로. */
    private static MasterProductOptionItem bomItem(MasterProduct master, Product product) {
        MasterProductOption option = MasterProductOption.builder()
                .id(master.getId() * 10).masterProduct(master).name("1세트").build();
        return MasterProductOptionItem.builder().option(option).product(product).quantity(1).build();
    }

    // ---------------------------------------------------------------- ① 기준가 갱신

    @Test
    void testReflectTrueUpdatesProductPrice() {
        Product product = product(33L, new BigDecimal("3000.00"));

        service.updateBasePrice(purchase(product, new BigDecimal("4000.0000"), 3, true, TODAY));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(33L);
        assertThat(captor.getValue().getPrice()).isEqualByComparingTo("4000");
    }

    @Test
    void testReflectFalseDoesNotTouchProductPrice() {
        // 프로모션 매입: 실원가는 손익에 쓰되 기준가는 움직이지 않는다(D3).
        service.updateBasePrice(purchase(product(33L, new BigDecimal("3000.00")),
                new BigDecimal("1000.0000"), 3, false, TODAY));

        verify(productRepository, never()).save(any());
    }

    @Test
    void testNullUnitPriceDoesNotTouchProductPrice() {
        service.updateBasePrice(purchase(product(33L, new BigDecimal("3000.00")), null, 3, true, TODAY));

        verify(productRepository, never()).save(any());
    }

    @Test
    void testNegativeQuantityDoesNotTouchProductPrice() {
        // 정정(음수) 행은 기준가를 정의하지 않는다.
        service.updateBasePrice(purchase(product(33L, new BigDecimal("3000.00")),
                new BigDecimal("4000.0000"), -3, true, TODAY));

        verify(productRepository, never()).save(any());
    }

    @Test
    void testAddPurchaseDoesNotTriggerPropagation() {
        // 🔴 ① 은 즉시, ② 는 사용자가 확정할 때. 매입 입력이 판매가를 건드리면 D4 가 무너진다.
        service.updateBasePrice(purchase(product(33L, null), new BigDecimal("4000.0000"), 3, true, TODAY));

        verify(productRepository).save(any());
        verifyNoInteractions(masterPropagationService);
    }

    // ---------------------------------------------------------------- ② 대상 산출

    @Test
    void testPreviewFindsProductsFromPurchaseHistory() {
        Product product = product(33L, new BigDecimal("3000.00"));
        MasterProduct master = MasterProduct.builder().id(7L).name("양말 마스터").build();
        ProductListing cell = cell(101L, master, ListingStatus.SELLING, "CP-1");

        given(purchaseRecordRepository.findPurchasedOnOrAfter(any()))
                .willReturn(List.of(purchase(product, new BigDecimal("4000.0000"), 3, true, TODAY)));
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(33L)))
                .willReturn(List.of(bomItem(master, product)));
        given(productListingRepository.findByMasterProductIdIn(List.of(7L))).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(List.of(101L)))
                .willReturn(List.of(option(201L, cell, GeneratedContentSource.AUTO)));
        given(generatedProductDataRepository.findByProductListingIdIn(List.of(101L)))
                .willReturn(List.of(GeneratedProductData.builder().productListing(cell).build()));

        PropagationPreview preview = service.preview(null);

        assertThat(preview.totalMasters()).isEqualTo(1);
        assertThat(preview.totalCells()).isEqualTo(1);
        assertThat(preview.masters().get(0).masterName()).isEqualTo("양말 마스터");
        assertThat(preview.masters().get(0).optionCount()).isEqualTo(1);
        assertThat(preview.skipped()).isEmpty();
    }

    @Test
    void testPreviewExcludesPromotionalPurchases() {
        // 프로모션 매입만 있는 물품은 기준가를 움직인 적이 없으므로 파급 대상이 아니다.
        given(purchaseRecordRepository.findPurchasedOnOrAfter(any()))
                .willReturn(List.of(purchase(product(33L, null), new BigDecimal("4000.0000"), 3, false, TODAY)));

        PropagationPreview preview = service.preview(null);

        assertThat(preview.totalMasters()).isZero();
        verifyNoInteractions(masterProductOptionItemRepository);
    }

    @Test
    void testPreviewExcludesUnknownAmountAndCorrections() {
        Product product = product(33L, null);
        given(purchaseRecordRepository.findPurchasedOnOrAfter(any())).willReturn(List.of(
                purchase(product, null, 3, true, TODAY),                          // 금액 미상
                purchase(product, new BigDecimal("4000.0000"), -3, true, TODAY))); // 정정(음수)

        PropagationPreview preview = service.preview(null);

        assertThat(preview.totalMasters()).isZero();
        verifyNoInteractions(masterProductOptionItemRepository);
    }

    @Test
    void testPreviewSinceParameterOverridesDefault() {
        given(purchaseRecordRepository.findPurchasedOnOrAfter(any())).willReturn(List.of());

        service.preview(null);
        service.preview(TODAY.minusDays(60));

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(purchaseRecordRepository, org.mockito.Mockito.times(2))
                .findPurchasedOnOrAfter(captor.capture());
        assertThat(captor.getAllValues().get(0)).isEqualTo(TODAY.minusDays(7));   // 기본 7일
        assertThat(captor.getAllValues().get(1)).isEqualTo(TODAY.minusDays(60));  // 넓힌 창
    }

    @Test
    void testPreviewExcludesManualOptions() {
        Product product = product(33L, new BigDecimal("3000.00"));
        MasterProduct master = MasterProduct.builder().id(7L).name("양말 마스터").build();
        ProductListing cell = cell(101L, master, ListingStatus.SELLING, "CP-1");

        given(purchaseRecordRepository.findPurchasedOnOrAfter(any()))
                .willReturn(List.of(purchase(product, new BigDecimal("4000.0000"), 3, true, TODAY)));
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(33L)))
                .willReturn(List.of(bomItem(master, product)));
        given(productListingRepository.findByMasterProductIdIn(List.of(7L))).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(List.of(101L)))
                .willReturn(List.of(option(201L, cell, GeneratedContentSource.MANUAL_OVERRIDE)));
        given(generatedProductDataRepository.findByProductListingIdIn(List.of(101L)))
                .willReturn(List.of(GeneratedProductData.builder().productListing(cell).build()));

        PropagationPreview preview = service.preview(null);

        assertThat(preview.totalCells()).isZero();
        assertThat(preview.skipped()).hasSize(1);
        assertThat(preview.skipped().get(0).reason()).isEqualTo(PropagationPreview.SkipReason.MANUAL);
        assertThat(preview.skipped().get(0).cellId()).isEqualTo(101L);
    }

    @Test
    void testPreviewCountsAffectedCells() {
        // 마스터 2 · 대상 셀 5 · 그 밖에 DRAFT / 미승인 / 자산없음 셀은 skipped 로 빠진다.
        Product product = product(33L, new BigDecimal("3000.00"));
        MasterProduct masterA = MasterProduct.builder().id(7L).name("A 마스터").build();
        MasterProduct masterB = MasterProduct.builder().id(8L).name("B 마스터").build();

        List<ProductListing> cells = List.of(
                cell(101L, masterA, ListingStatus.SELLING, "CP-1"),
                cell(102L, masterA, ListingStatus.SELLING, "CP-2"),
                cell(103L, masterA, ListingStatus.SELLING, "CP-3"),
                cell(104L, masterB, ListingStatus.SELLING, "CP-4"),
                cell(105L, masterB, ListingStatus.SUSPENDED, "CP-5"),
                cell(106L, masterB, ListingStatus.DRAFT, null),          // DRAFT
                cell(107L, masterB, ListingStatus.SUBMITTED, null),      // 미승인(마켓 id 없음)
                cell(108L, masterB, ListingStatus.SELLING, "CP-8"));     // 자산 없음

        given(purchaseRecordRepository.findPurchasedOnOrAfter(any()))
                .willReturn(List.of(purchase(product, new BigDecimal("4000.0000"), 3, true, TODAY)));
        given(masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(33L)))
                .willReturn(List.of(bomItem(masterA, product), bomItem(masterB, product)));
        given(productListingRepository.findByMasterProductIdIn(List.of(7L, 8L))).willReturn(cells);
        given(productListingOptionRepository.findByProductListingIdIn(anyList())).willReturn(
                cells.stream().map(c -> option(c.getId() + 100, c, GeneratedContentSource.AUTO)).toList());
        given(generatedProductDataRepository.findByProductListingIdIn(anyList())).willReturn(
                cells.stream().filter(c -> c.getId() != 108L)
                        .map(c -> GeneratedProductData.builder().productListing(c).build())
                        .toList());

        PropagationPreview preview = service.preview(null);

        assertThat(preview.totalMasters()).isEqualTo(2);
        assertThat(preview.totalCells()).isEqualTo(5);
        assertThat(preview.masters()).extracting(PropagationPreview.AffectedMaster::cellCount)
                .containsExactly(3, 2);
        assertThat(preview.skipped()).extracting(PropagationPreview.SkippedCell::reason)
                .containsExactly(PropagationPreview.SkipReason.DRAFT,
                        PropagationPreview.SkipReason.NOT_APPROVED,
                        PropagationPreview.SkipReason.NO_ASSETS);
    }

    // ---------------------------------------------------------------- ② 확정 실행

    @Test
    void testApplyDelegatesToMasterPropagationService() {
        given(masterPropagationService.propagate(any()))
                .willReturn(PropagateResponse.builder().propagated(3).skipped(1).failed(0).build());

        PropagationApplyResult result = service.apply(List.of(7L, 8L));

        verify(masterPropagationService).propagate(7L);
        verify(masterPropagationService).propagate(8L);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.propagatedCells()).isEqualTo(6);
        assertThat(result.skippedCells()).isEqualTo(2);
        assertThat(result.results()).hasSize(2);
    }

    @Test
    void testApplyPartialFailureReported() {
        // 하나가 실패해도 나머지는 커밋된다 — 전체를 되돌리지 않는다(2609_02 패턴).
        given(masterPropagationService.propagate(7L))
                .willReturn(PropagateResponse.builder().propagated(3).skipped(0).failed(0).build());
        willThrow(new IllegalStateException("boom")).given(masterPropagationService).propagate(eq(8L));

        PropagationApplyResult result = service.apply(List.of(7L, 8L));

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.propagatedCells()).isEqualTo(3);
        assertThat(result.results().get(1).error()).isEqualTo("boom");
    }

    @Test
    void testApplyDoesNotCallChannelPush() {
        // ③ 은 이 기능 밖이다. 이 서비스는 채널 클라이언트에 의존조차 하지 않고(컴파일 단계 보장),
        // 실행 중에도 파급 서비스 외에는 아무것도 건드리지 않는다.
        given(masterPropagationService.propagate(7L))
                .willReturn(PropagateResponse.builder().propagated(1).skipped(0).failed(0).build());

        service.apply(List.of(7L));

        verifyNoInteractions(productRepository, purchaseRecordRepository, productListingRepository,
                productListingOptionRepository, generatedProductDataRepository,
                masterProductOptionItemRepository);
    }

    // ---------------------------------------------------------------- 괴리 목록

    @Test
    void testDeviationExcludesNonReflectingPurchases() {
        Product reflected = product(33L, new BigDecimal("3000.00"));
        Product promotional = product(34L, new BigDecimal("3000.00"));
        given(purchaseRecordRepository.findPurchasedOnOrAfter(any())).willReturn(List.of(
                purchase(reflected, new BigDecimal("4500.0000"), 3, true, TODAY),
                purchase(promotional, new BigDecimal("500.0000"), 3, false, TODAY)));

        List<CostDeviation> deviations = service.deviations(20);

        assertThat(deviations).hasSize(1);
        assertThat(deviations.get(0).productId()).isEqualTo(33L);
        assertThat(deviations.get(0).diffRate()).isEqualByComparingTo("0.5000");
    }
}
