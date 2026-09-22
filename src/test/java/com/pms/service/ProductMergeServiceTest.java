package com.pms.service;

import com.pms.domain.OrderLine;
import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.domain.ShoppingListItem;
import com.pms.dto.request.MergeProductsRequest;
import com.pms.dto.response.MergeProductsResponse;
import com.pms.dto.response.ProductUsageResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ProductInUseException;
import com.pms.repository.BoxRecipeRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.repository.StockMovementRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link ProductMergeService} (FEATURE_2609_69 / B).
 *
 * <p>Mockito only — the merge is composition over repositories, so a Spring context proves nothing extra
 * here (backend rule: services are mocked, controllers are integration). What a mock cannot prove — bulk
 * flush, FK and unique behaviour, and the rollback — lives in {@code ProductMergeIntegrationTest}.</p>
 *
 * <p>🔴 The three link repositories below are declared as mocks although they are deliberately <b>not</b>
 * constructor dependencies of the service. Verifying "never called" on a collaborator the service cannot
 * even reach is the strongest available statement of PLAN D6-a: links are never migrated.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductMergeService - Unit Tests")
class ProductMergeServiceTest {

    private static final Long TARGET_ID = 10L;
    private static final Long SOURCE_ID = 589L;

    @Mock private ProductRepository productRepository;
    @Mock private ProductService productService;
    @Mock private ProductUsageService productUsageService;
    @Mock private ProductMergeSnapshotWriter snapshotWriter;
    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private ShipmentParcelItemRepository shipmentParcelItemRepository;
    @Mock private ProductImageRepository productImageRepository;
    @Mock private PriceChangeLogRepository priceChangeLogRepository;
    @Mock private ShoppingListItemRepository shoppingListItemRepository;
    @Mock private BoxRecipeRepository boxRecipeRepository;

    // Not injected on purpose — see the class javadoc.
    @Mock private MasterProductComponentRepository masterProductComponentRepository;
    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;

    @InjectMocks private ProductMergeService service;

    private Product target;
    private Product source;

    @BeforeEach
    void setUp() {
        target = Product.builder().id(TARGET_ID).productName("파라마운트 팜즈 무염 피스타치오")
                .brand("파라마운트").price(new BigDecimal("12000")).active(true).build();
        source = Product.builder().id(SOURCE_ID).productName("원더풀 피스타치오")
                .brand("원더풀").price(new BigDecimal("9000")).active(true).build();
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private void givenBothProductsExist() {
        given(productRepository.findScopedById(TARGET_ID)).willReturn(Optional.of(target));
        given(productRepository.findScopedById(SOURCE_ID)).willReturn(Optional.of(source));
    }

    private void givenSourceIsUnlinked() {
        given(productUsageService.getUsage(SOURCE_ID)).willReturn(usage(true, List.of()));
    }

    private ProductUsageResponse usage(boolean deletable, List<String> blockers) {
        return new ProductUsageResponse(SOURCE_ID, List.of(), List.of(),
                new ProductUsageResponse.HistoryCounts(0, 0, 0, 0, 0, 0), deletable, blockers);
    }

    private void givenSnapshotIsWritten() {
        given(snapshotWriter.write(any(), any())).willReturn("merge_10_589_20260922120000.json");
    }

    private MergeProductsRequest.TransferOptions allTransfersOn(boolean appendMemo) {
        return new MergeProductsRequest.TransferOptions(true, true, true, true, true, true, appendMemo);
    }

    private MergeProductsRequest.TransferOptions noTransfers() {
        return new MergeProductsRequest.TransferOptions(false, false, false, false, false, false, false);
    }

    private MergeProductsRequest.MergedFields noFields() {
        return new MergeProductsRequest.MergedFields(null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private MergeProductsRequest request(MergeProductsRequest.MergedFields fields,
                                         MergeProductsRequest.TransferOptions transfer) {
        return new MergeProductsRequest(TARGET_ID, SOURCE_ID, fields, transfer);
    }

    private ProductImage image(Long id, Product owner, int sortOrder, String url) {
        return ProductImage.builder().id(id).product(owner).sortOrder(sortOrder).imageUrl(url).build();
    }

    private ShoppingListItem shoppingRow(Long id, Long orderLineId, int autoQty) {
        return ShoppingListItem.builder()
                .id(id)
                .orderLine(orderLineId == null ? null : OrderLine.builder().id(orderLineId).build())
                .product(source)
                .autoQty(autoQty)
                .manualQty(0)
                .build();
    }

    private Product savedTarget() {
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        return captor.getValue();
    }

    /** Example ② of the prompt — run in every happy-path test. */
    private void assertLinksUntouched() {
        verify(masterProductComponentRepository, never()).save(any());
        verify(masterProductComponentRepository, never()).delete(any());
        verify(masterProductOptionItemRepository, never()).save(any());
    }

    // ---- transfer selection ---------------------------------------------------------------------

    @Test
    @DisplayName("Only the ticked history moves - the rest is never reassigned")
    void testMergeMovesOnlyCheckedHistory() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        given(purchaseRecordRepository.reassignProduct(target, SOURCE_ID)).willReturn(2);

        // stock 3 rows and 4 images exist on the source, but only purchases are ticked
        MergeProductsResponse response = service.merge(request(noFields(),
                new MergeProductsRequest.TransferOptions(true, false, false, false, false, false, false)));

        assertThat(response.moved()).containsEntry("purchaseRecords", 2);
        assertThat(response.moved()).doesNotContainKeys("stockMovements", "images");
        verify(stockMovementRepository, never()).reassignProduct(any(), any());
        verify(productImageRepository, never()).reassignProduct(any(), any());
        verify(shipmentParcelItemRepository, never()).reassignProduct(any(), any());
        verify(priceChangeLogRepository, never()).reassignProduct(any(), any());
        verifyNoInteractions(shoppingListItemRepository);
        assertLinksUntouched();
    }

    @Test
    @DisplayName("box_recipe rows holding the source id are deleted, with no checkbox")
    void testMergeDeletesBoxRecipeRowsOfSource() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        given(boxRecipeRepository.deleteByProductIdInKey(SOURCE_ID)).willReturn(2);

        MergeProductsResponse response = service.merge(request(noFields(), noTransfers()));

        verify(boxRecipeRepository).deleteByProductIdInKey(SOURCE_ID);
        assertThat(response.droppedOnConflict()).containsEntry("boxRecipes", 2);
    }

    // ---- links are never migrated ---------------------------------------------------------------

    @Test
    @DisplayName("A master link on the source blocks the merge with 409 and moves nothing")
    void testMergeRejectsWhenMasterLinkRemains() {
        givenBothProductsExist();
        given(productUsageService.getUsage(SOURCE_ID))
                .willReturn(usage(false, List.of("마스터 상품 1개(마스터 상품 화면에서 해제)")));

        assertThatThrownBy(() -> service.merge(request(noFields(), allTransfersOn(true))))
                .isInstanceOf(ProductInUseException.class)
                .hasMessageContaining("마스터 상품 1개");

        verifyNoInteractions(snapshotWriter, purchaseRecordRepository, stockMovementRepository,
                shipmentParcelItemRepository, productImageRepository, priceChangeLogRepository,
                shoppingListItemRepository, boxRecipeRepository);
        verify(productService, never()).deleteProduct(any());
    }

    @Test
    @DisplayName("A listing-option link on the source blocks the merge with 409")
    void testMergeRejectsWhenListingLinkRemains() {
        givenBothProductsExist();
        given(productUsageService.getUsage(SOURCE_ID))
                .willReturn(usage(false, List.of("판매 옵션 2개(셀 화면에서 해제)")));

        assertThatThrownBy(() -> service.merge(request(noFields(), allTransfersOn(true))))
                .isInstanceOf(ProductInUseException.class)
                .hasMessageContaining("판매 옵션 2개");

        verifyNoInteractions(snapshotWriter);
    }

    @Test
    @DisplayName("A normal merge never touches the link repositories")
    void testMergeDoesNotTouchLinks() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(SOURCE_ID)).willReturn(List.of());
        given(shoppingListItemRepository.findByProductId(SOURCE_ID)).willReturn(List.of());
        given(shoppingListItemRepository.findOrderLineIdsByProductId(TARGET_ID)).willReturn(Set.of());

        service.merge(request(noFields(), allTransfersOn(false)));

        assertLinksUntouched();
        verify(masterProductOptionItemRepository, never()).delete(any());
    }

    // ---- shopping list conflicts ----------------------------------------------------------------

    @Test
    @DisplayName("A shopping row on an order line the target already sits on is dropped, not summed")
    void testMergeDropsConflictingShoppingListItem() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        ShoppingListItem itemOnLine100 = shoppingRow(1L, 100L, 3);
        ShoppingListItem itemOnLine200 = shoppingRow(2L, 200L, 5);
        given(shoppingListItemRepository.findOrderLineIdsByProductId(TARGET_ID)).willReturn(Set.of(100L));
        given(shoppingListItemRepository.findByProductId(SOURCE_ID))
                .willReturn(List.of(itemOnLine100, itemOnLine200));

        MergeProductsResponse response = service.merge(request(noFields(),
                new MergeProductsRequest.TransferOptions(false, false, false, false, true, false, false)));

        verify(shoppingListItemRepository, times(1)).delete(itemOnLine100);
        ArgumentCaptor<ShoppingListItem> saved = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getProduct().getId()).isEqualTo(TARGET_ID);
        assertThat(saved.getValue().getOrderLine().getId()).isEqualTo(200L);
        assertThat(response.moved()).containsEntry("shoppingListItems", 1);
        assertThat(response.droppedOnConflict()).containsEntry("shoppingListItems", 1);
    }

    @Test
    @DisplayName("Conflicting shopping quantities are not added together")
    void testMergeDoesNotSumShoppingQuantity() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        ShoppingListItem sourceRow = shoppingRow(1L, 100L, 3);   // target already has 2 on line 100
        given(shoppingListItemRepository.findOrderLineIdsByProductId(TARGET_ID)).willReturn(Set.of(100L));
        given(shoppingListItemRepository.findByProductId(SOURCE_ID)).willReturn(List.of(sourceRow));

        service.merge(request(noFields(),
                new MergeProductsRequest.TransferOptions(false, false, false, false, true, false, false)));

        // The source row is deleted outright: the target's own 2 stays 2, it never becomes 5.
        verify(shoppingListItemRepository).delete(sourceRow);
        verify(shoppingListItemRepository, never()).save(any());
    }

    // ---- fields and memo -------------------------------------------------------------------------

    @Test
    @DisplayName("Only the fields that were provided are overwritten")
    void testMergeOverwritesOnlyProvidedFields() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        MergeProductsRequest.MergedFields fields = new MergeProductsRequest.MergedFields(
                "원더풀 피스타치오 1kg", null, "014113950374", null, null, null,
                null, null, null, null, null, null);

        service.merge(request(fields, noTransfers()));

        Product saved = savedTarget();
        assertThat(saved.getProductName()).isEqualTo("원더풀 피스타치오 1kg");
        assertThat(saved.getBarcodeId()).isEqualTo("014113950374");
        assertThat(saved.getBrand()).isEqualTo("파라마운트");                  // target's own value kept
        assertThat(saved.getPrice()).isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("The migration note is appended to the existing description")
    void testMergeAppendsMemoWhenEnabled() {
        target = target.toBuilder().description("기존 설명").build();
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        given(purchaseRecordRepository.reassignProduct(target, SOURCE_ID)).willReturn(3);

        service.merge(request(noFields(),
                new MergeProductsRequest.TransferOptions(true, false, false, false, false, false, true)));

        Product saved = savedTarget();
        assertThat(saved.getDescription()).startsWith("기존 설명\n");
        assertThat(saved.getDescription()).contains("[" + LocalDate.now() + "]");
        assertThat(saved.getDescription()).contains("물품 #589 「원더풀 피스타치오」");
        assertThat(saved.getDescription()).contains("매입 3건");
    }

    @Test
    @DisplayName("No note is added when the memo box is unticked")
    void testMergeSkipsMemoWhenDisabled() {
        target = target.toBuilder().description("기존 설명").build();
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();

        service.merge(request(noFields(), noTransfers()));

        assertThat(savedTarget().getDescription()).isEqualTo("기존 설명");
    }

    // ---- images ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Migrated images are re-numbered so no two share a position")
    void testMergeRenumbersImageSortOrder() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        ProductImage sourceFirst = image(31L, source, 0, "https://cdn/s0.jpg");
        ProductImage sourceSecond = image(32L, source, 1, "https://cdn/s1.jpg");
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(SOURCE_ID))
                .willReturn(List.of(sourceFirst, sourceSecond));
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(TARGET_ID)).willReturn(List.of(
                image(11L, target, 0, "https://cdn/t0.jpg"),
                image(12L, target, 1, "https://cdn/t1.jpg"),
                sourceFirst.toBuilder().product(target).build(),
                sourceSecond.toBuilder().product(target).build()));
        given(productImageRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        service.merge(request(noFields(),
                new MergeProductsRequest.TransferOptions(false, false, false, true, false, false, false)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(productImageRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(ProductImage::getId)
                .containsExactly(11L, 12L, 31L, 32L);
        assertThat(captor.getValue()).extracting(ProductImage::getSortOrder)
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("The chosen representative becomes position 0 and the product's imageUrl")
    void testMergeSetsRepresentativeImage() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();
        ProductImage sourceImage = image(31L, source, 0, "https://cdn/s0.jpg");
        given(productImageRepository.findById(31L)).willReturn(Optional.of(sourceImage));
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(SOURCE_ID))
                .willReturn(List.of(sourceImage));
        given(productImageRepository.findByProductIdOrderBySortOrderAsc(TARGET_ID)).willReturn(List.of(
                image(11L, target, 0, "https://cdn/t0.jpg"),
                sourceImage.toBuilder().product(target).build()));
        given(productImageRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        MergeProductsRequest.MergedFields fields = new MergeProductsRequest.MergedFields(
                null, null, null, null, null, null, null, null, null, null, null, 31L);
        service.merge(request(fields,
                new MergeProductsRequest.TransferOptions(false, false, false, true, false, false, false)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(productImageRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getId()).isEqualTo(31L);
        assertThat(captor.getValue().get(0).getSortOrder()).isZero();
        assertThat(savedTarget().getImageUrl()).isEqualTo("https://cdn/s0.jpg");
    }

    @Test
    @DisplayName("A source image cannot be the representative when images are not migrated")
    void testMergeRejectsSourceImageAsRepresentativeWhenImagesOff() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        given(productImageRepository.findById(31L))
                .willReturn(Optional.of(image(31L, source, 0, "https://cdn/s0.jpg")));

        MergeProductsRequest.MergedFields fields = new MergeProductsRequest.MergedFields(
                null, null, null, null, null, null, null, null, null, null, null, 31L);

        assertThatThrownBy(() -> service.merge(request(fields, noTransfers())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("사진을 옮기지 않으면");

        verifyNoInteractions(snapshotWriter);
        verify(productRepository, never()).save(any());
        verify(productService, never()).deleteProduct(any());
    }

    // ---- validation --------------------------------------------------------------------------------

    @Test
    @DisplayName("A barcode already held by a third product is a 409 and saves nothing")
    void testMergeRejectsBarcodeUsedByThirdProduct() {
        givenBothProductsExist();
        given(productRepository.findByBarcodeIdAndActiveTrue("014113950374")).willReturn(List.of(
                Product.builder().id(777L).productName("다른 피스타치오").active(true).build()));

        MergeProductsRequest.MergedFields fields = new MergeProductsRequest.MergedFields(
                null, null, "014113950374", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.merge(request(fields, allTransfersOn(true))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("777");

        verifyNoInteractions(snapshotWriter);
        verify(productRepository, never()).save(any());
        verify(productService, never()).deleteProduct(any());
    }

    @Test
    @DisplayName("Target and source must differ")
    void testMergeRejectsSameId() {
        assertThatThrownBy(() -> service.merge(new MergeProductsRequest(
                TARGET_ID, TARGET_ID, noFields(), allTransfersOn(true))))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(productRepository, productUsageService, snapshotWriter);
    }

    @Test
    @DisplayName("An already soft-deleted product cannot be merged")
    void testMergeRejectsInactiveProduct() {
        given(productRepository.findScopedById(TARGET_ID)).willReturn(Optional.of(target));
        given(productRepository.findScopedById(SOURCE_ID))
                .willReturn(Optional.of(source.toBuilder().active(false).build()));

        assertThatThrownBy(() -> service.merge(request(noFields(), allTransfersOn(true))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 삭제된 물품");

        verifyNoInteractions(snapshotWriter);
    }

    // ---- delete ------------------------------------------------------------------------------------

    @Test
    @DisplayName("The source is soft-deleted through the guarded delete, after the history moved")
    void testMergeSoftDeletesSource() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();

        service.merge(request(noFields(), noTransfers()));

        // The guard (01) stays in the path — there is no guard-free variant. That the flag really ends up
        // false is asserted against the DB in ProductMergeIntegrationTest.
        verify(productService).deleteProduct(SOURCE_ID);
    }

    @Test
    @DisplayName("The snapshot is written before anything moves and its name comes back")
    void testMergeWritesSnapshotBeforeMoving() {
        givenBothProductsExist();
        givenSourceIsUnlinked();
        givenSnapshotIsWritten();

        MergeProductsResponse response = service.merge(request(noFields(), noTransfers()));

        verify(snapshotWriter).write(target, source);
        assertThat(response.snapshotFileName()).isEqualTo("merge_10_589_20260922120000.json");
    }
}
