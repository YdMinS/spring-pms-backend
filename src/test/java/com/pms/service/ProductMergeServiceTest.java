package com.pms.service;

import com.pms.domain.OrderLine;
import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.domain.ProductPurchasePlace;
import com.pms.domain.PurchasePlace;
import com.pms.domain.Seller;
import com.pms.domain.ShoppingListItem;
import com.pms.dto.request.MergeProductsRequest;
import com.pms.dto.response.MergeProductsResponse;
import com.pms.exception.BusinessException;
import com.pms.repository.BoxRecipeRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductPurchasePlaceRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerDebtRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.stock.LotLedgerService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
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
 * <p>🔴 The two link repositories below are declared as mocks although they are deliberately <b>not</b>
 * constructor dependencies of the service. Since FEATURE_2611_09 the master links do move, but only through
 * {@link MergeMasterLinkService} — verifying "never called" on a repository the service cannot even reach keeps
 * it that way (one owner for the master rules).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductMergeService - Unit Tests")
class ProductMergeServiceTest {

    private static final Long TARGET_ID = 10L;
    private static final Long SOURCE_ID = 589L;

    @Mock private ProductRepository productRepository;
    @Mock private ProductService productService;
    @Mock private MergeMasterLinkService mergeMasterLinkService;
    @Mock private ProductMergeSnapshotWriter snapshotWriter;
    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private ShipmentParcelItemRepository shipmentParcelItemRepository;
    @Mock private ProductImageRepository productImageRepository;
    @Mock private PriceChangeLogRepository priceChangeLogRepository;
    @Mock private ShoppingListItemRepository shoppingListItemRepository;
    @Mock private BoxRecipeRepository boxRecipeRepository;
    @Mock private ProductPurchasePlaceRepository productPurchasePlaceRepository;
    @Mock private LotLedgerService lotLedgerService;
    @Mock private SellerDebtRepository sellerDebtRepository;

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

    /** The source sits in no master — the master step has nothing to do (FEATURE_2611_09). */
    private static final MergeMasterLinkService.Plan NO_MASTERS =
            new MergeMasterLinkService.Plan(0, List.of(), List.of());

    private void givenNoMasterLinks() {
        given(mergeMasterLinkService.plan(target, source, null)).willReturn(NO_MASTERS);
    }

    private void givenSnapshotIsWritten() {
        given(snapshotWriter.write(any(), any(), any())).willReturn("merge_10_589_20260922120000.json");
    }

    private MergeProductsRequest.TransferOptions allTransfersOn(boolean appendMemo) {
        return new MergeProductsRequest.TransferOptions(true, true, true, true, true, true, appendMemo);
    }

    private MergeProductsRequest.TransferOptions noTransfers() {
        return new MergeProductsRequest.TransferOptions(false, false, false, false, false, false, false);
    }

    private MergeProductsRequest.MergedFields noFields() {
        return new MergeProductsRequest.MergedFields(null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private MergeProductsRequest request(MergeProductsRequest.MergedFields fields,
                                         MergeProductsRequest.TransferOptions transfer) {
        return new MergeProductsRequest(TARGET_ID, SOURCE_ID, fields, transfer, null);
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
        givenNoMasterLinks();
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
        givenNoMasterLinks();
        givenSnapshotIsWritten();
        given(boxRecipeRepository.deleteByProductIdInKey(SOURCE_ID)).willReturn(2);

        MergeProductsResponse response = service.merge(request(noFields(), noTransfers()));

        verify(boxRecipeRepository).deleteByProductIdInKey(SOURCE_ID);
        assertThat(response.droppedOnConflict()).containsEntry("boxRecipes", 2);
    }

    // ---- lot records · master links (FEATURE_2611_09) ----------------------------------------------

    /**
     * FEATURE_2610_19 D150: a source with a lot record (here an undecided purchase) must move its purchase records
     * and its stock rows together — moving only the purchase records is a 400 before anything moves.
     */
    @Test
    @DisplayName("A source with lot records must move purchase records and stock rows together")
    void testMergeRejectsSplitLotRecords() {
        givenBothProductsExist();
        given(purchaseRecordRepository.existsByProduct_IdAndSellerIsNull(SOURCE_ID)).willReturn(true);
        MergeProductsRequest.TransferOptions onlyPurchases =
                new MergeProductsRequest.TransferOptions(true, false, true, true, true, true, false);

        assertThatThrownBy(() -> service.merge(request(noFields(), onlyPurchases)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("영수증 배정 · 영수증 미등록 · 판매자 간 빚 기록이 있는 물품은 「매입 이력」과 「재고 이동」을 함께 옮겨야 합니다.");

        verifyNoInteractions(snapshotWriter);
        verify(purchaseRecordRepository, never()).reassignProduct(any(), any());
        verify(stockMovementRepository, never()).reassignProduct(any(), any());
    }

    @Test
    @DisplayName("A master refusal (FEATURE_2611_09 D4) stops the merge before anything moves")
    void testMergeMasterRefusalMovesNothing() {
        givenBothProductsExist();
        given(mergeMasterLinkService.plan(target, source, null)).willThrow(new BusinessException(
                "남길 물품과 버릴 물품이 함께 들어 있는 마스터 상품이 있습니다: 견과 세트(#7). "
                        + "마스터 구성 편집에서 한쪽을 먼저 빼 주세요.", HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.merge(request(noFields(), allTransfersOn(true))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("견과 세트(#7)");

        verifyNoInteractions(snapshotWriter, purchaseRecordRepository, stockMovementRepository,
                shipmentParcelItemRepository, productImageRepository, priceChangeLogRepository,
                shoppingListItemRepository, boxRecipeRepository);
        verify(mergeMasterLinkService, never()).moveLinks(any(), any(), any());
        verify(productService, never()).deleteProduct(any());
    }

    @Test
    @DisplayName("Master links move after the snapshot; emptied masters are deleted right before the source (D1 · D20)")
    void testMergeMovesMasterLinksAndDeletesEmptiedMastersLast() {
        givenBothProductsExist();
        MergeMasterLinkService.Plan plan = new MergeMasterLinkService.Plan(2, List.of(7L),
                List.of(new MergeMasterLinkService.Pair(8L, 9L, Map.of())));
        given(mergeMasterLinkService.plan(target, source, null)).willReturn(plan);
        givenSnapshotIsWritten();
        given(mergeMasterLinkService.moveLinks(plan, target, source)).willReturn(List.of(7L, 9L));

        MergeProductsResponse response = service.merge(request(noFields(), noTransfers()));

        InOrder order = inOrder(snapshotWriter, mergeMasterLinkService, productRepository, productService);
        order.verify(snapshotWriter).write(target, source, plan);
        order.verify(mergeMasterLinkService).moveLinks(plan, target, source);
        order.verify(productRepository).save(any());
        order.verify(mergeMasterLinkService).deleteEmptiedMasters(plan);
        order.verify(productService).deleteProduct(SOURCE_ID);
        assertThat(response.changedMasterIds()).containsExactly(7L, 9L);
        assertThat(response.moved()).containsEntry("masterProducts", 2);
    }

    @Test
    @DisplayName("The migration note counts the masters the source was in (D11)")
    void testMergeMemoCountsMasters() {
        givenBothProductsExist();
        given(mergeMasterLinkService.plan(target, source, null))
                .willReturn(new MergeMasterLinkService.Plan(2, List.of(7L, 8L), List.of()));
        givenSnapshotIsWritten();

        service.merge(request(noFields(),
                new MergeProductsRequest.TransferOptions(false, false, false, false, false, false, true)));

        assertThat(savedTarget().getDescription()).contains("마스터 2개");
    }

    @Test
    @DisplayName("The merge itself never writes the link repositories — MergeMasterLinkService does")
    void testMergeDoesNotTouchLinks() {
        givenBothProductsExist();
        givenNoMasterLinks();
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
        givenNoMasterLinks();
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
        givenNoMasterLinks();
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

    @Test
    @DisplayName("A manual row collides only with the target's manual row of the same seller (FEATURE_2610_09 D18)")
    void testMergeManualRowCollidesPerSeller() {
        givenBothProductsExist();
        givenNoMasterLinks();
        givenSnapshotIsWritten();
        ShoppingListItem manualOfSeller3 = shoppingRow(1L, null, 0).toBuilder()
                .seller(Seller.builder().id(3L).build()).manualQty(2).build();
        ShoppingListItem manualOfSeller9 = shoppingRow(2L, null, 0).toBuilder()
                .seller(Seller.builder().id(9L).build()).manualQty(4).build();
        given(shoppingListItemRepository.findManualSellerIdsByProductId(TARGET_ID)).willReturn(Set.of(3L));
        given(shoppingListItemRepository.findByProductId(SOURCE_ID))
                .willReturn(List.of(manualOfSeller3, manualOfSeller9));

        service.merge(request(noFields(),
                new MergeProductsRequest.TransferOptions(false, false, false, false, true, false, false)));

        verify(shoppingListItemRepository).delete(manualOfSeller3);
        ArgumentCaptor<ShoppingListItem> saved = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(saved.capture());
        assertThat(saved.getValue().getSeller().getId()).isEqualTo(9L);
        assertThat(saved.getValue().getProduct().getId()).isEqualTo(TARGET_ID);
    }

    // ---- fields and memo -------------------------------------------------------------------------

    @Test
    @DisplayName("Only the fields that were provided are overwritten")
    void testMergeOverwritesOnlyProvidedFields() {
        givenBothProductsExist();
        givenNoMasterLinks();
        givenSnapshotIsWritten();
        MergeProductsRequest.MergedFields fields = new MergeProductsRequest.MergedFields(
                "원더풀 피스타치오 1kg", null, "014113950374", null, null,
                null, null, null, null, null, null, null, null);

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
        givenNoMasterLinks();
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
        givenNoMasterLinks();
        givenSnapshotIsWritten();

        service.merge(request(noFields(), noTransfers()));

        assertThat(savedTarget().getDescription()).isEqualTo("기존 설명");
    }

    // ---- images ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Migrated images are re-numbered so no two share a position")
    void testMergeRenumbersImageSortOrder() {
        givenBothProductsExist();
        givenNoMasterLinks();
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
        givenNoMasterLinks();
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
                null, null, null, null, null, null, null, null, null, null, null, null, 31L);
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
        given(productImageRepository.findById(31L))
                .willReturn(Optional.of(image(31L, source, 0, "https://cdn/s0.jpg")));

        MergeProductsRequest.MergedFields fields = new MergeProductsRequest.MergedFields(
                null, null, null, null, null, null, null, null, null, null, null, null, 31L);

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
                null, null, "014113950374", null, null, null, null, null, null, null, null, null, null);

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
                TARGET_ID, TARGET_ID, noFields(), allTransfersOn(true), null)))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(productRepository, mergeMasterLinkService, snapshotWriter);
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

    // ---- purchase places (FEATURE_2609_76 / D16) ---------------------------------------------------

    @Test
    @DisplayName("Purchase places are never picked — the target gains the places only the source had")
    void testMergeAddsSourceOnlyPurchasePlacesToTarget() {
        givenBothProductsExist();
        givenNoMasterLinks();
        givenSnapshotIsWritten();
        PurchasePlace emart = PurchasePlace.builder().id(1L).name("이마트").sortOrder(0).build();
        PurchasePlace noBrand = PurchasePlace.builder().id(3L).name("노브랜드").sortOrder(2).build();
        given(productPurchasePlaceRepository.findWithPlaceByProductIdIn(List.of(TARGET_ID, SOURCE_ID)))
                .willReturn(List.of(
                        ProductPurchasePlace.builder().id(100L).product(target).purchasePlace(emart).build(),
                        ProductPurchasePlace.builder().id(200L).product(source).purchasePlace(emart).build(),
                        ProductPurchasePlace.builder().id(201L).product(source).purchasePlace(noBrand).build()));

        service.merge(request(noFields(), noTransfers()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductPurchasePlace>> captor = ArgumentCaptor.forClass(List.class);
        verify(productPurchasePlaceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);                               // 이마트 is already on the target
        assertThat(captor.getValue().get(0).getProduct().getId()).isEqualTo(TARGET_ID);
        assertThat(captor.getValue().get(0).getPurchasePlace().getId()).isEqualTo(3L);
    }

    // ---- delete ------------------------------------------------------------------------------------

    @Test
    @DisplayName("The source is soft-deleted through the guarded delete, after the history moved")
    void testMergeSoftDeletesSource() {
        givenBothProductsExist();
        givenNoMasterLinks();
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
        givenNoMasterLinks();
        givenSnapshotIsWritten();

        MergeProductsResponse response = service.merge(request(noFields(), noTransfers()));

        verify(snapshotWriter).write(target, source, NO_MASTERS);
        assertThat(response.snapshotFileName()).isEqualTo("merge_10_589_20260922120000.json");
    }
}
