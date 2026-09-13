package com.pms.service.packing;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 🔴 THE SINGLE OWNER of the box-memory key rule (FEATURE_2609_40 / PLAN D22).
 *
 * <p>The key is {@code (product_id:quantity)} pairs <b>sorted by product id ascending</b> and joined with
 * {@code '|'}:</p>
 *
 * <pre>
 *   [(45,1), (12,2)]  ->  "12:2|45:1"
 *   [(12,2), (45,1)]  ->  "12:2|45:1"   // same combination, same key — scan order is irrelevant
 *   [(12,3), (45,1)]  ->  "12:3|45:1"   // quantity is part of the key
 * </pre>
 *
 * <p>🔴 Sorting IS the rule. The side that records a memory (packing console, piece 03) and the side that
 * looks candidates up must produce byte-identical keys; if the same combination could yield two different
 * keys, the memory would never match again and the recommendation would be permanently silent. That is why
 * this class exists instead of an inline string concatenation at each call site.</p>
 *
 * <p>⚠️ Repeated products are merged, not appended: {@code [(12,1),(12,1)]} and {@code [(12,2)]} are the same
 * combination and must produce {@code "12:2"} — two scans of the same item are two units, not two lines.</p>
 *
 * <p>⚠️ Nothing else belongs in the key — no order, option, seller or date (D22).</p>
 */
public final class BoxRecipeKey {

    private BoxRecipeKey() {
    }

    /**
     * Build the canonical key for a set of packed items.
     *
     * @param items items placed in the box; null/empty yields an empty string (no combination = no memory)
     * @return the sorted key, e.g. {@code "12:2|45:1"}
     * @throws IllegalArgumentException if an item has no product id or a quantity &lt;= 0
     */
    public static String of(java.util.Collection<RecipeItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        // TreeMap = the sort, and the merge of repeated products, in one step.
        Map<Long, Integer> merged = new TreeMap<>();
        for (RecipeItem item : items) {
            if (item == null || item.productId() == null) {
                throw new IllegalArgumentException("물품 식별자가 없습니다");
            }
            if (item.quantity() == null || item.quantity() <= 0) {
                throw new IllegalArgumentException("수량은 1 이상이어야 합니다");
            }
            merged.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return merged.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining("|"));
    }
}
