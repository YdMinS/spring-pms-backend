package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.domain.ShoppingListItem;
import com.pms.dto.request.MergeProductsRequest;
import com.pms.dto.response.MergeProductsResponse;
import com.pms.dto.response.ProductUsageResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ProductInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.BoxRecipeRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.repository.StockMovementRepository;

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
 * <p>🔴 <b>Nothing here is automatic.</b> Only the values a person picked and the history a person ticked
 * move to the target; the source is then soft-deleted through the ordinary guarded delete. Everything runs
 * in one transaction — a failure leaves both products exactly as they were (PLAN D6).</p>
 *
 * <p>🔴 <b>Links are never migrated</b> (PLAN D6-a): {@code master_product_component} rows stay put. A source
 * that still carries one is refused with 409 <b>before</b> any row moves — migrating history first and then
 * failing the delete would make the operator come back.</p>
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
    private final ProductUsageService productUsageService;
    private final ProductMergeSnapshotWriter snapshotWriter;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ShipmentParcelItemRepository shipmentParcelItemRepository;
    private final ProductImageRepository productImageRepository;
    private final PriceChangeLogRepository priceChangeLogRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final BoxRecipeRepository boxRecipeRepository;

    /**
     * Run one merge.
     *
     * @throws ResourceNotFoundException either product (or the chosen representative image) does not exist
     * @throws BusinessException         400 for a bad request, 409 for a barcode clash or a remaining link
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
        assertSourceUnlinked(sourceId);
        ProductImage representative = resolveRepresentative(fields.representativeImageId(),
                targetId, sourceId, transfer.images());

        // Snapshot BEFORE anything moves (PLAN D7). A write failure throws and rolls the merge back.
        String snapshotFileName = snapshotWriter.write(target, source);

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

        String representativeUrl = renumberGallery(targetId, sourceImageIds, representative);

        applyFields(target, fields, representativeUrl, transfer.appendMemo(), sourceId, sourceName, moved);

        // Soft delete last, through the ordinary guarded path (01). If the guard fires here, a link was
        // missed and the whole transaction rolls back — there is deliberately no guard-free variant.
        productService.deleteProduct(sourceId);

        log.info("Merged product {} into {} (moved={}, dropped={}, snapshot={})",
                sourceId, targetId, moved, dropped, snapshotFileName);
        return new MergeProductsResponse(targetId, moved, dropped, snapshotFileName);
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
     * <p>A no-op otherwise: the source is soft-deleted, and a code nobody else wants may stay on the hidden
     * row. {@code saveAndFlush} is deliberate — the release has to reach the database before the target's
     * UPDATE does, or the unique key fires on the instant both rows hold the code.</p>
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
     * 🔴 Checked before anything moves. The delete at the end would catch this too, but by then the history
     * has already been migrated and the operator has to start over.
     */
    private void assertSourceUnlinked(Long sourceId) {
        ProductUsageResponse usage = productUsageService.getUsage(sourceId);
        if (!usage.deletable()) {
            throw new ProductInUseException(usage.blockers());
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
     * <p>⚠️ Manual rows carry no order line (their "one row per product" rule is owned by the service, not
     * the constraint) — the target's manual row wins the same way.</p>
     */
    private void moveShoppingListItems(Product target, Long sourceId,
                                       Map<String, Integer> moved, Map<String, Integer> dropped) {
        Set<Long> targetLines = new LinkedHashSet<>(
                shoppingListItemRepository.findOrderLineIdsByProductId(target.getId()));
        boolean targetHasManualRow =
                shoppingListItemRepository.existsByOrderLineIsNullAndProduct_Id(target.getId());

        int movedRows = 0;
        int droppedRows = 0;
        for (ShoppingListItem row : shoppingListItemRepository.findByProductId(sourceId)) {
            boolean collides = row.getOrderLine() == null
                    ? targetHasManualRow
                    : targetLines.contains(row.getOrderLine().getId());
            if (collides) {
                shoppingListItemRepository.delete(row);
                droppedRows++;
            } else {
                shoppingListItemRepository.save(row.toBuilder().product(target).build());
                if (row.getOrderLine() == null) {
                    targetHasManualRow = true;
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
        if (fields.store() != null) {
            builder.store(fields.store());
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

    /** One line appended to the target's description so the history's origin stays readable (PLAN D6-c). */
    private String migrationNote(Long sourceId, String sourceName, Map<String, Integer> moved) {
        List<String> parts = new ArrayList<>();
        addPart(parts, moved, "purchaseRecords", "매입", "건");
        addPart(parts, moved, "stockMovements", "재고", "건");
        addPart(parts, moved, "shipmentItems", "발송", "건");
        addPart(parts, moved, "images", "사진", "장");
        addPart(parts, moved, "shoppingListItems", "구매목록", "건");
        addPart(parts, moved, "priceChangeLogs", "가격이력", "건");

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
