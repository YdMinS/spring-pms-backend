package com.pms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Product;
import com.pms.exception.BusinessException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-merge snapshot of both products (FEATURE_2609_69 / B, PLAN D7).
 *
 * <p>There is no rollback feature — this file is the evidence that makes an undo possible by hand. It is
 * written <b>before</b> a single row moves and holds: both products' current field values, the row ids of
 * the eight tables that reference a product, and the {@code box_recipe} rows about to be deleted.</p>
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
     * The eight tables carrying a {@code product_id} FK (PLAN, verified 2026-09-22 by
     * {@code grep -l 'JoinColumn(name = "product_id"' domain/*.java}). Links are listed too even though the
     * merge never moves them — the snapshot records the state, not the plan.
     *
     * <p>🔴 2609_71: {@code product_listing_product} 는 목록에서 빠졌다 — 테이블이 사라졌고(changeset 097),
     * 셀 옵션의 구성품은 {@code master_product_option_item} 하나가 갖는다. 없는 테이블을 조회하면
     * 스냅샷 실패 = 병합 전체 실패다.</p>
     */
    private static final List<String> REFERENCE_TABLES = List.of(
            "master_product_component",
            "master_product_option_item",
            "purchase_record",
            "stock_movement",
            "shipment_parcel_item",
            "product_image",
            "shopping_list_item",
            "price_change_log");

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;

    @Value("${app.merge.snapshot-dir}")
    private String snapshotDir;

    /**
     * Write the snapshot and return its file <b>name</b> (never the path — the directory is a server-side
     * setting and is not exposed to the client).
     *
     * @throws BusinessException when the file cannot be written; the caller must let it roll the merge back
     */
    public String write(Product target, Product source) {
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
        fields.put("store", product.getStore());
        fields.put("price", product.getPrice() == null ? null : product.getPrice().toPlainString());
        fields.put("description", product.getDescription());
        fields.put("netContent", product.getNetContent());
        fields.put("netContentUnit", product.getNetContentUnit());
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

    /** H2 and MySQL hand back different numeric types for the same BIGINT column. */
    private List<Object> normalise(List<?> rows) {
        return rows.stream()
                .map(value -> value instanceof Number number ? (Object) number.longValue() : value)
                .toList();
    }
}
