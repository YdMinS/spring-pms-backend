package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.domain.ProductPurchasePlace;
import com.pms.domain.ShoppingListItem;
import com.pms.domain.StockMovementType;
import com.pms.dto.request.MergeProductsRequest;
import com.pms.dto.response.MergeProductsResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.BoxRecipeRepository;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merge two duplicate products into one (FEATURE_2609_69 / B).
 *
 * <p>🔴 <b>Nothing here is automatic.</b> Of the fields and the history, only the values a person picked and the
 * history a person ticked move to the target; the source is then soft-deleted through the ordinary guarded
 * delete. Everything runs in one transaction — a failure leaves both products exactly as they were (PLAN D6).</p>
 *
 * <p>🔁 <b>Master links move with every merge</b> (FEATURE_2611_09 / D1 — reverses 2609_69 D6-a), through
 * {@link MergeMasterLinkService}: its refusals (a master holding both products, a choice missing) are raised
 * <b>before</b> any row moves (D6) — migrating history first and then failing would make the operator come back.
 * The masters it empties are deleted as the last step before the source's delete (D20); their edited S3 photos
 * are deleted only after the commit (FEATURE_2611_13 D6), so a rollback keeps them.</p>
 *
 * <p>🔴 <b>No cost recalculation is triggered</b> (PLAN D10). A line's cost is baked in when it ships, so
 * moving purchase history cannot change what already left; future lines using the surviving product's
 * latest purchase is the intended behaviour.</p>
 *
 * <p>⚠️ Tenant isolation flows through {@link Product}'s {@code @TenantId} via
 * {@code ProductRepository.findScopedById} — another tenant's product is simply not found (404). Do not add
 * manual tenant conditions.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProductMergeService {

    private final ProductRepository productRepository;
    private final ProductService productService;
    private final MergeMasterLinkService mergeMasterLinkService;
    private final ProductMergeSnapshotWriter snapshotWriter;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ShipmentParcelItemRepository shipmentParcelItemRepository;
    private final ProductImageRepository productImageRepository;
    private final PriceChangeLogRepository priceChangeLogRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final BoxRecipeRepository boxRecipeRepository;
    private final ProductPurchasePlaceRepository productPurchasePlaceRepository;
    private final LotLedgerService lotLedgerService;
    private final SellerDebtRepository sellerDebtRepository;

    /**
     * Run one merge.
     *
     * @throws ResourceNotFoundException either product (or the chosen representative image) does not exist
     * @throws BusinessException         400 for a bad request or a bad master option choice, 409 for a barcode
     *                                   clash or a master the links cannot move into (FEATURE_2611_09 / D4 · D27)
     */
    @Transactional
    public MergeProductsResponse merge(MergeProductsRequest request) {
        Long targetId = request.targetProductId();
        Long sourceId = request.sourceProductId();
        if (targetId.equals(sourceId)) {
            throw new BusinessException("같은 물품끼리는 병합할 수 없습니다.", HttpStatus.BAD_REQUEST);
        }

        Product target = requireProduct(targetId);
        Product source = requireProduct(sourceId);
        requireActive(target);
        requireActive(source);

        MergeProductsRequest.MergedFields fields = request.fields();
        MergeProductsRequest.TransferOptions transfer = request.transfer();

        assertBarcodeFree(fields.barcodeId(), targetId, sourceId);
        ProductImage representative = resolveRepresentative(fields.representativeImageId(),
                targetId, sourceId, transfer.images());
        assertLotRecordsMoveTogether(sourceId, transfer);
        // FEATURE_2611_09 / D6: every master refusal is raised here, before anything moves.
        MergeMasterLinkService.Plan masterPlan =
                mergeMasterLinkService.plan(target, source, request.masterOptionChoices());

        // Snapshot BEFORE anything moves (PLAN D7 · FEATURE_2611_09 / D22). A write failure throws and rolls the
        // merge back.
        String snapshotFileName = snapshotWriter.write(target, source, masterPlan);

        // 🔴 Release the source's barcode BEFORE the target claims it. The usual merge is "the surviving
        // product takes the duplicate's barcode", and the source keeps every column through its soft
        // delete — so with uq_products_tenant_barcode (changeset 098) in place the two rows would hold the
        // same code for an instant and the UPDATE would fail with a raw constraint violation. Order is the
        // whole point: this flushes first, the target adopts the code afterwards. The original value lives
        // on in the snapshot written just above.
        releaseSourceBarcode(source, fields.barcodeId() != null ? fields.barcodeId() : target.getBarcodeId());

        // The source name is read for the migration note; capture it before the bulk updates detach entities.
        String sourceName = source.getProductName();
        // Gallery order of the source, captured before the bulk update makes the two galleries
        // indistinguishable — the merged gallery keeps the target's images first (Step 4).
        List<Long> sourceImageIds = transfer.images() || representative != null
                ? productImageRepository.findByProductIdOrderBySortOrderAsc(sourceId).stream()
                        .map(ProductImage::getId).toList()
                : List.of();

        Map<String, Integer> moved = new LinkedHashMap<>();
        Map<String, Integer> dropped = new LinkedHashMap<>();

        if (transfer.purchaseRecords()) {
            moved.put("purchaseRecords", purchaseRecordRepository.reassignProduct(target, sourceId));
        }
        if (transfer.stockMovements()) {
            moved.put("stockMovements", stockMovementRepository.reassignProduct(target, sourceId));
            // FEATURE_2610_19 D132: the lots changed product — rewrite the debts of both products.
            lotLedgerService.rebuildDebts(sourceId);
            lotLedgerService.rebuildDebts(target.getId());
        }
        if (transfer.shipmentItems()) {
            moved.put("shipmentItems", shipmentParcelItemRepository.reassignProduct(target, sourceId));
        }
        if (transfer.images()) {
            moved.put("images", productImageRepository.reassignProduct(target, sourceId));
        }
        if (transfer.priceChangeLogs()) {
            moved.put("priceChangeLogs", priceChangeLogRepository.reassignProduct(target, sourceId));
        }
        if (transfer.shoppingListItems()) {
            moveShoppingListItems(target, sourceId, moved, dropped);
        }

        // box_recipe always, with no checkbox: the source id is baked into a sorted string key that cannot
        // be rewritten without colliding with the target's own key (PLAN D12).
        dropped.put("boxRecipes", boxRecipeRepository.deleteByProductIdInKey(sourceId));

        // FEATURE_2611_09 / D1 · D11: the master links, with no checkbox (D2). Counted before applyFields so the
        // migration note carries it.
        List<Long> changedMasterIds = mergeMasterLinkService.moveLinks(masterPlan, target, source);
        moved.put("masterProducts", masterPlan.sourceMasterCount());

        String representativeUrl = renumberGallery(targetId, sourceImageIds, representative);

        applyFields(target, fields, representativeUrl, transfer.appendMemo(), sourceId, sourceName, moved);

        mergePurchasePlaces(target, sourceId);

        // FEATURE_2611_09 / D20: the emptied masters go right before the source (order kept). Their edited S3
        // photos are deleted only after the commit (FEATURE_2611_13 D6), so a rollback below keeps the files.
        mergeMasterLinkService.deleteEmptiedMasters(masterPlan);

        // Soft delete last, through the ordinary guarded path (01). If the guard fires here, a link was
        // missed and the whole transaction rolls back — there is deliberately no guard-free variant (D9).
        productService.deleteProduct(sourceId);

        log.info("Merged product {} into {} (moved={}, dropped={}, snapshot={}, masters={})",
                sourceId, targetId, moved, dropped, snapshotFileName, changedMasterIds);
        return new MergeProductsResponse(targetId, moved, dropped, snapshotFileName, changedMasterIds);
    }

    private Product requireProduct(Long id) {
        return productRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    private void requireActive(Product product) {
        if (!Boolean.TRUE.equals(product.getActive())) {
            throw new BusinessException("이미 삭제된 물품입니다.", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The point of a merge is to remove a duplicate barcode, so it must not create another one: a third
     * active product already owning the chosen barcode is a 409.
     */
    private void assertBarcodeFree(String barcodeId, Long targetId, Long sourceId) {
        if (barcodeId == null || barcodeId.isBlank()) {
            return;
        }
        for (Product other : productRepository.findByBarcodeIdAndActiveTrue(barcodeId.trim())) {
            if (!other.getId().equals(targetId) && !other.getId().equals(sourceId)) {
                throw new BusinessException(
                        "이 바코드를 가진 다른 물품이 있습니다: " + other.getId() + " " + other.getProductName(),
                        HttpStatus.CONFLICT);
            }
        }
    }

    /**
     * Blank the source's barcode when the merged product is taking that very code over.
     *
     * <p>A no-op otherwise: {@code ProductService.deleteProduct} at the end of the merge blanks the
     * source's barcode anyway, so a code nobody else wants needs no special handling here. This method
     * exists for the ORDER: {@code saveAndFlush} pushes the release to the database before the target's
     * UPDATE, and the target adopts the code long before the delete runs — without it the unique key
     * fires on the instant both rows hold the code.</p>
     */
    private void releaseSourceBarcode(Product source, String mergedBarcodeId) {
        String sourceBarcode = source.getBarcodeId();
        if (sourceBarcode == null || mergedBarcodeId == null
                || !sourceBarcode.trim().equals(mergedBarcodeId.trim())) {
            return;
        }
        productRepository.saveAndFlush(source.toBuilder().barcodeId(null).build());
    }

    /**
     * FEATURE_2610_19 D150: when the source product has a lot record — a receipt assignment, an undecided intake row,
     * an undecided purchase or a borrowed-goods debt — its purchase records and its stock rows must move together.
     * Moving only one of them splits an assignment from the intake row it points at (D130), and the per-seller stock
     * and debts go wrong. Only the source is checked: the target's rows do not move.
     */
    private void assertLotRecordsMoveTogether(Long sourceId, MergeProductsRequest.TransferOptions transfer) {
        if (transfer.purchaseRecords() && transfer.stockMovements()) {
            return;
        }
        boolean hasLotRecord = purchaseRecordRepository.existsByProduct_IdAndSourceMovementIsNotNull(sourceId)
                || purchaseRecordRepository.existsByProduct_IdAndSellerIsNull(sourceId)
                || stockMovementRepository.existsByProductIdAndSellerIsNullAndMovementType(
                        sourceId, StockMovementType.STOCK_IN)
                || sellerDebtRepository.existsByProductId(sourceId);
        if (hasLotRecord) {
            throw new BusinessException(
                    "영수증 배정 · 영수증 미등록 · 판매자 간 빚 기록이 있는 물품은 「매입 이력」과 「재고 이동」을 함께 옮겨야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The chosen representative must survive the merge: a source image that is not being migrated would be
     * buried with the source, leaving the target pointing at a hidden photo (PLAN D13).
     */
    private ProductImage resolveRepresentative(Long representativeImageId, Long targetId, Long sourceId,
                                               boolean imagesTransferred) {
        if (representativeImageId == null) {
            return null;
        }
        ProductImage image = productImageRepository.findById(representativeImageId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", representativeImageId));
        Long ownerId = image.getProduct().getId();
        if (ownerId.equals(sourceId)) {
            if (!imagesTransferred) {
                throw new BusinessException(
                        "사진을 옮기지 않으면 그 사진을 대표로 지정할 수 없습니다.", HttpStatus.BAD_REQUEST);
            }
            return image;
        }
        if (!ownerId.equals(targetId)) {
            throw new BusinessException(
                    "병합 대상 물품의 사진이 아닙니다.", HttpStatus.BAD_REQUEST);
        }
        return image;
    }

    /**
     * Row-wise, not a bulk update: {@code uq_shopping_list_item (order_line_id, product_id)} is the one
     * unique constraint a merge can hit. A collision means the same order line lists both duplicates — the
     * source row is deleted and 🔴 <b>quantities are never summed</b> (PLAN D8), or the purchase quantity
     * doubles.
     *
     * <p>⚠️ Manual rows carry no order line (their "one row per (product × seller)" rule is owned by the service,
     * not the constraint) — the target's manual row of the same seller wins the same way (FEATURE_2610_09 D18).</p>
     */
    private void moveShoppingListItems(Product target, Long sourceId,
                                       Map<String, Integer> moved, Map<String, Integer> dropped) {
        Set<Long> targetLines = new LinkedHashSet<>(
                shoppingListItemRepository.findOrderLineIdsByProductId(target.getId()));
        Set<Long> targetManualSellers = new LinkedHashSet<>(
                shoppingListItemRepository.findManualSellerIdsByProductId(target.getId()));

        int movedRows = 0;
        int droppedRows = 0;
        for (ShoppingListItem row : shoppingListItemRepository.findByProductId(sourceId)) {
            boolean collides = row.getOrderLine() == null
                    ? targetManualSellers.contains(row.getSeller().getId())
                    : targetLines.contains(row.getOrderLine().getId());
            if (collides) {
                shoppingListItemRepository.delete(row);
                droppedRows++;
            } else {
                shoppingListItemRepository.save(row.toBuilder().product(target).build());
                if (row.getOrderLine() == null) {
                    targetManualSellers.add(row.getSeller().getId());
                } else {
                    targetLines.add(row.getOrderLine().getId());
                }
                movedRows++;
            }
        }
        moved.put("shoppingListItems", movedRows);
        dropped.put("shoppingListItems", droppedRows);
    }

    /**
     * Re-number the merged gallery to {@code 0..n-1} and return the url of its first image.
     *
     * <p>🔴 Both galleries started at {@code 0}, so migrating them as-is leaves two images claiming position
     * 0 — no constraint complains, the order just goes wrong. The target's own images keep their order and
     * the ones from the source follow. A chosen representative is moved to the front, which is what
     * "representative" means here: {@code product_image} has no flag column and
     * {@code ProductImageService} already treats the first gallery image as the representative (PLAN D13).</p>
     *
     * @return the new first image's url, or null when the gallery is empty or needs no change
     */
    private String renumberGallery(Long targetId, List<Long> sourceImageIds, ProductImage representative) {
        if (sourceImageIds.isEmpty() && representative == null) {
            return null; // nothing arrived and no representative was chosen — leave the gallery alone
        }
        List<ProductImage> gallery = productImageRepository.findByProductIdOrderBySortOrderAsc(targetId);
        if (gallery.isEmpty()) {
            return null;
        }
        List<ProductImage> ordered = new ArrayList<>();
        // Target-owned images first, in their existing order…
        gallery.stream().filter(image -> !sourceImageIds.contains(image.getId())).forEach(ordered::add);
        // …then the ones that came from the source, in the order they had there.
        for (Long sourceImageId : sourceImageIds) {
            gallery.stream().filter(image -> image.getId().equals(sourceImageId)).findFirst()
                    .ifPresent(ordered::add);
        }
        if (representative != null) {
            ordered.stream().filter(image -> image.getId().equals(representative.getId())).findFirst()
                    .ifPresent(image -> {
                        ordered.remove(image);
                        ordered.add(0, image);
                    });
        }

        List<ProductImage> renumbered = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            renumbered.add(ordered.get(i).toBuilder().sortOrder(i).build());
        }
        List<ProductImage> saved = productImageRepository.saveAll(renumbered);
        return saved.isEmpty() ? null : saved.get(0).getImageUrl();
    }

    /**
     * Overwrite the target with the values the operator picked. 🔴 A null field keeps the target's own
     * value — nothing of the source leaks in by itself (PLAN D9).
     */
    private void applyFields(Product target, MergeProductsRequest.MergedFields fields,
                             String representativeUrl, boolean appendMemo,
                             Long sourceId, String sourceName, Map<String, Integer> moved) {
        Product.ProductBuilder builder = target.toBuilder();
        if (fields.productName() != null) {
            builder.productName(fields.productName());
        }
        if (fields.brand() != null) {
            builder.brand(fields.brand());
        }
        if (fields.barcodeId() != null) {
            builder.barcodeId(fields.barcodeId());
        }
        if (fields.price() != null) {
            builder.price(fields.price());
        }
        if (fields.netContent() != null) {
            builder.netContent(fields.netContent());
        }
        if (fields.netContentUnit() != null) {
            builder.netContentUnit(fields.netContentUnit());
        }
        if (fields.countQuantity() != null) {
            builder.countQuantity(fields.countQuantity());
        }
        if (fields.countUnit() != null) {
            builder.countUnit(fields.countUnit());
        }
        if (fields.packageHeight() != null) {
            builder.packageHeight(fields.packageHeight());
        }
        if (fields.packageLength() != null) {
            builder.packageLength(fields.packageLength());
        }
        if (fields.packageWidth() != null) {
            builder.packageWidth(fields.packageWidth());
        }
        if (representativeUrl != null) {
            builder.imageUrl(representativeUrl);
        }

        String description = fields.description() != null ? fields.description() : target.getDescription();
        if (appendMemo) {
            String memo = migrationNote(sourceId, sourceName, moved);
            description = description == null || description.isBlank() ? memo : description + "\n" + memo;
        }
        builder.description(description);

        productRepository.save(builder.build());
    }

    /**
     * Purchase places are never picked — the target keeps its own and gains every place only the source had
     * (FEATURE_2609_76 / D16). The source's own links stay on it and are buried with its soft delete; a
     * soft-deleted product does not count as "using" a place (D9).
     */
    private void mergePurchasePlaces(Product target, Long sourceId) {
        List<ProductPurchasePlace> links =
                productPurchasePlaceRepository.findWithPlaceByProductIdIn(List.of(target.getId(), sourceId));
        Set<Long> targetPlaceIds = new LinkedHashSet<>();
        for (ProductPurchasePlace link : links) {
            if (link.getProduct().getId().equals(target.getId())) {
                targetPlaceIds.add(link.getPurchasePlace().getId());
            }
        }
        List<ProductPurchasePlace> added = new ArrayList<>();
        for (ProductPurchasePlace link : links) {
            if (link.getProduct().getId().equals(sourceId) && targetPlaceIds.add(link.getPurchasePlace().getId())) {
                added.add(ProductPurchasePlace.builder().product(target).purchasePlace(link.getPurchasePlace()).build());
            }
        }
        if (!added.isEmpty()) {
            productPurchasePlaceRepository.saveAll(added);
        }
    }

    /** One line appended to the target's description so the history's origin stays readable (PLAN D6-c). */
    private String migrationNote(Long sourceId, String sourceName, Map<String, Integer> moved) {
        List<String> parts = new ArrayList<>();
        addPart(parts, moved, "purchaseRecords", "매입", "건");
        addPart(parts, moved, "stockMovements", "재고", "건");
        addPart(parts, moved, "shipmentItems", "발송", "건");
        addPart(parts, moved, "images", "사진", "장");
        addPart(parts, moved, "shoppingListItems", "구매목록", "건");
        addPart(parts, moved, "priceChangeLogs", "가격이력", "건");
        addPart(parts, moved, "masterProducts", "마스터", "개");

        String head = "[" + LocalDate.now() + "] 물품 #" + sourceId + " 「" + sourceName + "」 정리하며 ";
        return parts.isEmpty() ? head + "가져온 기록 없이 병합함" : head + String.join(" · ", parts) + "을 가져옴";
    }

    private void addPart(List<String> parts, Map<String, Integer> moved, String key, String label, String unit) {
        Integer count = moved.get(key);
        if (count != null && count > 0) {
            parts.add(label + " " + count + unit);
        }
    }
}
