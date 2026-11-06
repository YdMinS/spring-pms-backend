package com.pms.service.category;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.response.LotteonCategoryImportResult;
import com.pms.dto.response.LotteonFeeImportResult;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonCredentials;
import com.pms.service.lotteon.LotteonKey;
import com.pms.service.lotteon.LotteonPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link LotteonCategoryImportService} implementation (FEATURE_2610_24).
 *
 * <p><b>Tree (D9 · D10)</b>: every page of 205 ({@code limit} {@value #PAGE_SIZE}, {@code skip} 0, 100, 200 …) is read
 * with one LotteON channel's key; {@code use_yn = N} rows are not imported. Nodes are written parent first (sorted by
 * {@code depth_no}). A leaf ({@code leaf_yn = Y}) is matched by (LOTTEON, {@code std_cat_id}) and gets its name /
 * parent refreshed; an intermediate keeps {@code code = null} and is matched by (parent, name). A node whose parent is
 * not imported is skipped. Nothing is deleted and no commission is touched here (D9).</p>
 *
 * <p><b>Fees (D46 · D48)</b>: a CSV line is placed on the one imported category whose name path (names joined with
 * 「 > 」) equals the line's path; a line matching zero or several categories is listed and not used. Every leaf takes
 * the value of its own line, else of the nearest upper category's line. Stored value = percent ÷ 100 ÷ (1 + fee VAT
 * rate), rounded HALF_UP to 4 places once. A re-import overwrites (D48).</p>
 */
@Service
@Transactional
public class LotteonCategoryImportServiceImpl implements LotteonCategoryImportService {

    static final int PAGE_SIZE = 100;
    static final String PATH_SEPARATOR = " > ";
    static final String TREE_REQUIRED = "롯데ON 카테고리 목록을 먼저 들여와야 합니다.";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final LotteonApiClient client;
    private final LotteonFeeCsvParser feeParser;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final BigDecimal feeVatRate;

    public LotteonCategoryImportServiceImpl(LotteonApiClient client,
                                            LotteonFeeCsvParser feeParser,
                                            PlatformCategoryRepository platformCategoryRepository,
                                            MarketplaceAccountRepository marketplaceAccountRepository,
                                            @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate) {
        this.client = client;
        this.feeParser = feeParser;
        this.platformCategoryRepository = platformCategoryRepository;
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.feeVatRate = feeVatRate;
    }

    @Override
    public LotteonCategoryImportResult importTree(Long accountId) {
        MarketplaceAccount account = marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
        LotteonKey key = LotteonCredentials.of(account);
        List<Node> nodes = readAll(key);
        nodes.sort(Comparator.comparingInt(Node::depth));

        Map<String, PlatformCategory> leafByCode = new HashMap<>();
        Map<String, PlatformCategory> nodeByParentAndName = new HashMap<>();
        for (PlatformCategory pc : platformCategoryRepository.findByPlatform(Platform.LOTTEON)) {
            if (pc.getCode() != null) {
                leafByCode.put(pc.getCode(), pc);
            } else {
                nodeByParentAndName.put(parentKey(pc.getParent(), pc.getName()), pc);
            }
        }

        Map<String, PlatformCategory> byId = new HashMap<>();
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (Node node : nodes) {
            PlatformCategory parent = null;
            if (node.depth() > 1) {
                parent = byId.get(node.parentId());
                if (parent == null) {
                    skipped++;
                    continue;
                }
            }
            PlatformCategory saved;
            if (node.leaf()) {
                PlatformCategory existing = leafByCode.get(node.id());
                if (existing != null) {
                    saved = platformCategoryRepository.save(existing.toBuilder()
                            .name(node.name()).parent(parent).build());
                    updated++;
                } else {
                    saved = platformCategoryRepository.save(PlatformCategory.builder()
                            .platform(Platform.LOTTEON).code(node.id()).name(node.name())
                            .parent(parent).commissionRate(null).build());
                    created++;
                }
            } else {
                String parentAndName = parentKey(parent, node.name());
                saved = nodeByParentAndName.get(parentAndName);
                if (saved == null) {
                    saved = platformCategoryRepository.save(PlatformCategory.builder()
                            .platform(Platform.LOTTEON).code(null).name(node.name())
                            .parent(parent).commissionRate(null).build());
                    nodeByParentAndName.put(parentAndName, saved);
                    created++;
                }
            }
            byId.put(node.id(), saved);
        }

        return LotteonCategoryImportResult.builder()
                .platformNodesCreated(created)
                .platformNodesUpdated(updated)
                .nodesProcessed(nodes.size())
                .nodesSkipped(skipped)
                .build();
    }

    @Override
    public LotteonFeeImportResult importFees(byte[] csv) {
        List<LotteonFeeRow> rows = feeParser.parse(csv);
        List<PlatformCategory> all = platformCategoryRepository.findByPlatform(Platform.LOTTEON);
        if (all.isEmpty()) {
            throw new IllegalArgumentException(TREE_REQUIRED);
        }
        Map<Long, PlatformCategory> byId = new HashMap<>();
        all.forEach(pc -> byId.put(pc.getId(), pc));
        Map<String, Integer> nodesPerPath = new HashMap<>();
        Map<Long, String> pathById = new HashMap<>();
        for (PlatformCategory pc : all) {
            String path = path(pc, byId);
            pathById.put(pc.getId(), path);
            nodesPerPath.merge(path, 1, Integer::sum);
        }

        Map<String, BigDecimal> percentByPath = new LinkedHashMap<>();
        List<LotteonFeeImportResult.Item> unmatched = new ArrayList<>();
        for (LotteonFeeRow row : rows) {
            if (nodesPerPath.getOrDefault(row.path(), 0) == 1) {
                percentByPath.put(row.path(), row.percent());
            } else {
                unmatched.add(new LotteonFeeImportResult.Item(row.path(), row.percent().toPlainString() + "%"));
            }
        }

        int updated = 0;
        int withoutFee = 0;
        for (PlatformCategory leaf : all) {
            if (leaf.getCode() == null) {
                continue;
            }
            BigDecimal percent = null;
            Set<Long> seen = new HashSet<>();
            for (PlatformCategory at = leaf; at != null && seen.add(at.getId());
                 at = at.getParent() == null ? null : byId.get(at.getParent().getId())) {
                percent = percentByPath.get(pathById.get(at.getId()));
                if (percent != null) {
                    break;
                }
            }
            if (percent == null) {
                withoutFee++;
                continue;
            }
            platformCategoryRepository.save(leaf.toBuilder().commissionRate(toStoredRate(percent)).build());
            updated++;
        }

        return LotteonFeeImportResult.builder()
                .leavesUpdated(updated)
                .unmatchedRows(unmatched)
                .leavesWithoutFee(withoutFee)
                .build();
    }

    /** D46 — percent ÷ 100 ÷ (1 + fee VAT rate), HALF_UP to 4 places, rounded once. */
    BigDecimal toStoredRate(BigDecimal percent) {
        return percent.divide(HUNDRED.multiply(BigDecimal.ONE.add(feeVatRate)), 4, RoundingMode.HALF_UP);
    }

    /** Every in-use row of every page of 205 — a short page (fewer than {@link #PAGE_SIZE}) is the last. */
    private List<Node> readAll(LotteonKey key) {
        List<Node> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int skip = 0; ; skip += PAGE_SIZE) {
            JsonNode page = client.getCategory(LotteonPaths.STANDARD_CATEGORY + "&skip=" + skip
                    + "&limit=" + PAGE_SIZE, key.apiKey());
            JsonNode items = page.path("itemList");
            for (JsonNode item : items) {
                JsonNode data = item.path("data");
                String id = data.path("std_cat_id").asText("");
                if (id.isBlank() || !seen.add(id) || !"Y".equals(data.path("use_yn").asText())) {
                    continue;
                }
                nodes.add(new Node(id, data.path("upr_std_cat_id").asText(""), data.path("std_cat_nm").asText(""),
                        data.path("depth_no").asInt(0), "Y".equals(data.path("leaf_yn").asText())));
            }
            if (items.size() < PAGE_SIZE) {
                return nodes;
            }
        }
    }

    /** The category's names from the top level down, joined with 「 > 」 (the CSV's path form). */
    private static String path(PlatformCategory pc, Map<Long, PlatformCategory> byId) {
        List<String> names = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (PlatformCategory at = pc; at != null && seen.add(at.getId());
             at = at.getParent() == null ? null : byId.get(at.getParent().getId())) {
            names.add(0, at.getName());
        }
        return String.join(PATH_SEPARATOR, names);
    }

    private static String parentKey(PlatformCategory parent, String name) {
        return (parent == null ? "0" : String.valueOf(parent.getId())) + "|" + name;
    }

    private record Node(String id, String parentId, String name, int depth, boolean leaf) {
    }
}
