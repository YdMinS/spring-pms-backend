package com.pms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Product;
import com.pms.exception.BusinessException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Clob;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pre-merge snapshot of both products (FEATURE_2609_69 / B, PLAN D7).
 *
 * <p>There is no rollback feature — this file is the evidence that makes an undo possible by hand. It is
 * written <b>before</b> a single row moves and holds: both products' current field values, the row ids of
 * the nine tables that reference a product, the {@code box_recipe} rows about to be deleted, and — since
 * FEATURE_2611_09 / D22 — under {@code masterLinks}: every master the merge deletes in full (master row,
 * components, options, option quantities, photos, cover mapping, collage rows) and every listing that moves
 * with its option links as they were before the move.</p>
 *
 * <p>🔴 <b>A write failure fails the merge.</b> No evidence means no way back, so the exception propagates
 * and rolls the whole transaction back — it is never downgraded to a warning.</p>
 *
 * <p>🔴 <b>The file lives outside the repository.</b> Path comes from {@code app.merge.snapshot-dir}; the
 * default {@code ${user.home}/.oclyx/merge_snapshots} is <b>inside the container</b> on dev/prod and
 * disappears on redeploy — those environments must point the property at a host-mounted directory.</p>
 *
 * <p>⚠️ Native queries, so Hibernate's {@code @TenantId} filter does not apply. That is intentional and
 * safe: every row is selected by the product id itself, and product ids are globally unique.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProductMergeSnapshotWriter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * The nine tables carrying a {@code product_id} FK (PLAN, verified 2026-09-22 by
     * {@code grep -l 'JoinColumn(name = "product_id"' domain/*.java}). The two link tables are listed with the
     * history ones — the merge rewrites their rows in place (FEATURE_2611_09 / D3), so these ids stay valid.
     *
     * <p>🔴 2609_71: {@code product_listing_product} left the list — the table is gone (changeset 097) and a
     * cell option's components live in {@code master_product_option_item} alone. Querying a missing table fails
     * the snapshot, and a failed snapshot fails the whole merge.</p>
     */
    private static final List<String> REFERENCE_TABLES = List.of(
            "master_product_component",
            "master_product_option_item",
            "purchase_record",
            "stock_movement",
            "shipment_parcel_item",
            "product_image",
            "shopping_list_item",
            "price_change_log",
            // FEATURE_2609_76 / D16: the merge adds the source's places to the target, so the target's link row
            // ids from BEFORE the merge are what tells an undo which rows to remove.
            "product_purchase_place");

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;

    @Value("${app.merge.snapshot-dir}")
    private String snapshotDir;

    /**
     * Write the snapshot and return its file <b>name</b> (never the path — the directory is a server-side
     * setting and is not exposed to the client).
     *
     * @param masterPlan what the merge does to the masters (FEATURE_2611_09 / D22)
     * @throws BusinessException when the file cannot be written; the caller must let it roll the merge back
     */
    public String write(Product target, Product source, MergeMasterLinkService.Plan masterPlan) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("generatedAt", LocalDateTime.now().toString());
        snapshot.put("targetProductId", target.getId());
        snapshot.put("sourceProductId", source.getId());
        snapshot.put("target", describe(target));
        snapshot.put("source", describe(source));

        Map<String, Object> references = new LinkedHashMap<>();
        for (String table : REFERENCE_TABLES) {
            Map<String, Object> perSide = new LinkedHashMap<>();
            perSide.put("target", rowIds(table, target.getId()));
            perSide.put("source", rowIds(table, source.getId()));
            references.put(table, perSide);
        }
        // box_recipe is the tenth reference and neither the FK grep nor information_schema finds it: the
        // product id is embedded in a sorted string key (PLAN D12). Those rows are deleted by the merge, so
        // their ids are the only trace left.
        references.put("box_recipe", Map.of("source", boxRecipeIds(source.getId())));
        snapshot.put("references", references);
        snapshot.put("masterLinks", masterLinks(masterPlan));

        String fileName = "merge_" + target.getId() + "_" + source.getId() + "_"
                + LocalDateTime.now().format(STAMP) + ".json";
        try {
            Path directory = Path.of(snapshotDir);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(fileName), objectMapper.writeValueAsString(snapshot));
        } catch (IOException e) {
            log.error("Failed to write merge snapshot to {}: {}", snapshotDir, e.getMessage());
            throw new BusinessException(
                    "병합 전 스냅샷을 저장하지 못해 병합을 중단했습니다. 스냅샷 경로 설정을 확인하세요.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        log.info("Wrote merge snapshot {} (target={}, source={})", fileName, target.getId(), source.getId());
        return fileName;
    }

    private Map<String, Object> describe(Product product) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", product.getId());
        fields.put("productName", product.getProductName());
        fields.put("brand", product.getBrand());
        fields.put("barcodeId", product.getBarcodeId());
        fields.put("price", product.getPrice() == null ? null : product.getPrice().toPlainString());
        fields.put("description", product.getDescription());
        fields.put("netContent", product.getNetContent());
        fields.put("netContentUnit", product.getNetContentUnit());
        fields.put("countQuantity", product.getCountQuantity());
        fields.put("countUnit", product.getCountUnit());
        fields.put("packageHeight", product.getPackageHeight());
        fields.put("packageLength", product.getPackageLength());
        fields.put("packageWidth", product.getPackageWidth());
        fields.put("imageUrl", product.getImageUrl());
        fields.put("active", product.getActive());
        return fields;
    }

    @SuppressWarnings("unchecked")
    private List<Object> rowIds(String table, Long productId) {
        // Table names come from the constant above, never from user input.
        Query query = entityManager.createNativeQuery("SELECT id FROM " + table + " WHERE product_id = :pid");
        query.setParameter("pid", productId);
        return normalise(query.getResultList());
    }

    @SuppressWarnings("unchecked")
    private List<Object> boxRecipeIds(Long productId) {
        Query query = entityManager.createNativeQuery(
                "SELECT id FROM box_recipe WHERE recipe_key LIKE CONCAT(:pid, ':%') "
                        + "OR recipe_key LIKE CONCAT('%|', :pid, ':%')");
        query.setParameter("pid", productId);
        return normalise(query.getResultList());
    }

    /**
     * FEATURE_2611_09 / D22: ① each deleted master in full ② each listing that moves, with the master and master
     * options it pointed at before the move.
     */
    private Map<String, Object> masterLinks(MergeMasterLinkService.Plan masterPlan) {
        List<Map<String, Object>> deletedMasters = new ArrayList<>();
        List<Map<String, Object>> movedListings = new ArrayList<>();
        for (MergeMasterLinkService.Pair pair : masterPlan.pairs()) {
            Long masterId = pair.deleteMasterId();
            String options = "SELECT id FROM master_product_option WHERE master_product_id = :id";
            String images = "SELECT id FROM master_product_image WHERE master_product_id = :id";
            Map<String, Object> master = new LinkedHashMap<>();
            master.put("masterProductId", masterId);
            master.put("movedToMasterProductId", pair.keepMasterId());
            master.put("master_product", rows("SELECT * FROM master_product WHERE id = :id", masterId));
            master.put("master_product_component",
                    rows("SELECT * FROM master_product_component WHERE master_product_id = :id", masterId));
            master.put("master_product_option",
                    rows("SELECT * FROM master_product_option WHERE master_product_id = :id", masterId));
            master.put("master_product_option_item", rows(
                    "SELECT * FROM master_product_option_item WHERE master_product_option_id IN (" + options + ")",
                    masterId));
            master.put("master_product_image",
                    rows("SELECT * FROM master_product_image WHERE master_product_id = :id", masterId));
            master.put("master_image_zone_assignment", rows(
                    "SELECT * FROM master_image_zone_assignment WHERE zone_id = '__source__' AND master_product_image_id IN ("
                            + images + ")", masterId));
            master.put("thumbnail_collage",
                    rows("SELECT * FROM thumbnail_collage WHERE master_product_id = :id", masterId));
            deletedMasters.add(master);

            for (Map<String, Object> listing : rows(
                    "SELECT id FROM product_listing WHERE master_product_id = :id ORDER BY id", masterId)) {
                Long listingId = ((Number) listing.get("id")).longValue();
                Map<String, Object> moved = new LinkedHashMap<>();
                moved.put("productListingId", listingId);
                moved.put("fromMasterProductId", masterId);
                moved.put("toMasterProductId", pair.keepMasterId());
                moved.put("options", rows("SELECT id, master_product_option_id FROM product_listing_option "
                        + "WHERE product_listing_id = :id ORDER BY id", listingId));
                movedListings.add(moved);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deletedMasters", deletedMasters);
        result.put("movedListings", movedListings);
        return result;
    }

    /**
     * Whole rows as column → value maps. Column names are lower-cased (H2 upper-cases them), large text columns
     * are read out as strings — a {@code java.sql.Clob} is not serialisable and would fail the merge.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String sql, Long id) {
        // SQL is built from fixed strings in this class, never from user input.
        Query query = entityManager.createNativeQuery(sql, Tuple.class);
        query.setParameter("id", id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tuple tuple : (List<Tuple>) query.getResultList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (TupleElement<?> element : tuple.getElements()) {
                row.put(element.getAlias().toLowerCase(Locale.ROOT), plain(tuple.get(element)));
            }
            result.add(row);
        }
        return result;
    }

    private static Object plain(Object value) {
        if (value instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (SQLException e) {
                throw new BusinessException(
                        "병합 전 스냅샷을 저장하지 못해 병합을 중단했습니다. 스냅샷 경로 설정을 확인하세요.",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof TemporalAccessor || value instanceof Date) {
            return value.toString();
        }
        return value;
    }

    /** H2 and MySQL hand back different numeric types for the same BIGINT column. */
    private List<Object> normalise(List<?> rows) {
        return rows.stream()
                .map(value -> value instanceof Number number ? (Object) number.longValue() : value)
                .toList();
    }
}
