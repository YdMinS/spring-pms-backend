package com.pms.service.esm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ESM category reads (FEATURE_2610_26): the ESM category tree (post 5, D12), the lowest Gmarket / Auction category
 * matched to an ESM leaf (post 13, D13) and the Gmarket site category tree (post 4, D67).
 */
@Component
@RequiredArgsConstructor
public class EsmCategories {

    private final EsmApiClient client;

    /** One ESM category row of post 5. */
    public record SdCategory(String code, String name, boolean leaf) {
    }

    /** D13: the first {@code IsLeaf} true Gmarket and Auction rows matched to an ESM leaf (null = none). */
    public record SiteLeaves(String gmarket, String auction) {
    }

    /** One site category of post 4 with its children list as returned ({@code subCats}). */
    public record SiteCategory(String code, String name, boolean leaf, List<SiteCategory> children) {
    }

    /** D12: the children of an ESM category — {@code "0"} = the top level. */
    public List<SdCategory> sdChildren(EsmKey key, String sdCatCode) {
        JsonNode body = client.get(String.format(EsmPaths.SD_CATEGORIES, sdCatCode), key);
        List<SdCategory> children = new ArrayList<>();
        for (JsonNode node : body.path("sdCategoryTree")) {
            children.add(new SdCategory(node.path("SDCategoryCode").asText(), node.path("SDCategoryName").asText(),
                    node.path("IsLeafCategory").asBoolean()));
        }
        return children;
    }

    /**
     * D13: one post 13 call → the first {@code IsLeaf} true row (response order) of {@code MatchedCategory.Gmkt} and of
     * {@code MatchedCategory.Iac} for an ESM leaf category.
     */
    public SiteLeaves matchedLeaves(EsmKey key, String sdCatCode) {
        JsonNode matched = client.get(String.format(EsmPaths.SD_CATEGORY_SITE_CATS, sdCatCode), key)
                .path("MatchedCategory");
        return new SiteLeaves(firstLeaf(matched.path("Gmkt")), firstLeaf(matched.path("Iac")));
    }

    private static String firstLeaf(JsonNode rows) {
        for (JsonNode row : rows) {
            if (row.path("IsLeaf").asBoolean()) {
                return row.path("CatCode").asText();
            }
        }
        return null;
    }

    /**
     * D67: one Gmarket site category with its direct children ({@code subCats}), read with a Gmarket-only token
     * (D6). {@code null} code = the top level.
     */
    public SiteCategory gmarketCategory(EsmKey key, String catCode) {
        String path = catCode == null ? EsmPaths.SITE_CATEGORIES : EsmPaths.SITE_CATEGORIES + "/" + catCode;
        JsonNode body = client.get(path, key, EsmSite.GMARKET);
        List<SiteCategory> children = new ArrayList<>();
        for (JsonNode sub : body.path("subCats")) {
            children.add(new SiteCategory(sub.path("catCode").asText(), sub.path("catName").asText(),
                    sub.path("isLeaf").asBoolean(), List.of()));
        }
        return new SiteCategory(body.path("catCode").asText(null), body.path("catName").asText(null),
                body.path("isLeaf").asBoolean(), children);
    }
}
