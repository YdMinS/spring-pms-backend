package com.pms.service.category;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.response.EsmCategoryImportResult;
import com.pms.dto.response.EsmFeeImportResult;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.service.esm.EsmCategories;
import com.pms.service.esm.EsmCredentials;
import com.pms.service.esm.EsmKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link EsmCategoryImportService} implementation (FEATURE_2610_26).
 *
 * <p><b>Tree (D12)</b>: ESM categories are read from {@code sd-cats/0} down, parents first. A leaf
 * ({@code IsLeafCategory} true) is matched by (ESM, code) and gets its name / parent refreshed; an intermediate keeps
 * {@code code = null} and is matched by (parent, name). Nothing is deleted and no commission is touched here.</p>
 *
 * <p><b>Fees (D14 · D62 · D63 · D64 · D67)</b>: the Gmarket site tree is walked once (Gmarket-only token) into
 * 「Gmarket leaf code → (major name, middle name)」 — middle = the level right under the top level, none when the
 * top level is itself a leaf. Every ESM leaf takes its first matched Gmarket leaf (post 13, the D13 row), then the
 * table row with the same major + middle, else the same major's 「전체」 row. Names are compared with all whitespace
 * removed. Stored value = table value ÷ (1 + fee VAT rate), HALF_UP to 4 places once, into both commission columns.
 * A leaf no row reaches keeps what it had.</p>
 */
@Service
@Transactional
public class EsmCategoryImportServiceImpl implements EsmCategoryImportService {

    static final String TREE_REQUIRED = "G마켓 · 옥션 카테고리 목록을 먼저 들여와야 합니다.";
    static final String WHOLE = "전체";

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final EsmCategories esmCategories;
    private final EsmFeeTableParser feeParser;
    private final BigDecimal feeVatRate;

    public EsmCategoryImportServiceImpl(MarketplaceAccountRepository marketplaceAccountRepository,
                                        PlatformCategoryRepository platformCategoryRepository,
                                        EsmCategories esmCategories,
                                        EsmFeeTableParser feeParser,
                                        @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate) {
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.platformCategoryRepository = platformCategoryRepository;
        this.esmCategories = esmCategories;
        this.feeParser = feeParser;
        this.feeVatRate = feeVatRate;
    }

    @Override
    public EsmCategoryImportResult importTree(Long accountId) {
        EsmKey key = EsmCredentials.of(findAccount(accountId));

        Map<String, PlatformCategory> leafByCode = new HashMap<>();
        Map<String, PlatformCategory> nodeByParentAndName = new HashMap<>();
        for (PlatformCategory pc : platformCategoryRepository.findByPlatform(Platform.ESM)) {
            if (pc.getCode() != null) {
                leafByCode.put(pc.getCode(), pc);
            } else {
                nodeByParentAndName.put(parentKey(pc.getParent(), pc.getName()), pc);
            }
        }

        int created = 0;
        int updated = 0;
        int processed = 0;
        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(null, "0"));
        while (!queue.isEmpty()) {
            Pending pending = queue.poll();
            for (EsmCategories.SdCategory node : esmCategories.sdChildren(key, pending.sdCode())) {
                processed++;
                PlatformCategory parent = pending.parent();
                if (node.leaf()) {
                    PlatformCategory existing = leafByCode.get(node.code());
                    if (existing != null) {
                        platformCategoryRepository.save(existing.toBuilder().name(node.name()).parent(parent).build());
                        updated++;
                    } else {
                        leafByCode.put(node.code(), platformCategoryRepository.save(PlatformCategory.builder()
                                .platform(Platform.ESM).code(node.code()).name(node.name()).parent(parent)
                                .build()));
                        created++;
                    }
                } else {
                    String parentKey = parentKey(parent, node.name());
                    PlatformCategory saved = nodeByParentAndName.get(parentKey);
                    if (saved == null) {
                        saved = platformCategoryRepository.save(PlatformCategory.builder()
                                .platform(Platform.ESM).code(null).name(node.name()).parent(parent).build());
                        nodeByParentAndName.put(parentKey, saved);
                        created++;
                    }
                    queue.add(new Pending(saved, node.code()));
                }
            }
        }
        return EsmCategoryImportResult.builder()
                .platformNodesCreated(created)
                .platformNodesUpdated(updated)
                .nodesProcessed(processed)
                .build();
    }

    @Override
    public EsmFeeImportResult importFees(Long accountId, byte[] xlsx) {
        EsmKey key = EsmCredentials.of(findAccount(accountId));
        List<EsmFeeRow> rows = feeParser.parse(xlsx);
        List<PlatformCategory> leaves = platformCategoryRepository.findByPlatform(Platform.ESM).stream()
                .filter(pc -> pc.getCode() != null)
                .toList();
        if (leaves.isEmpty()) {
            throw new IllegalArgumentException(TREE_REQUIRED);
        }

        Map<String, EsmFeeRow> rowByNames = new LinkedHashMap<>();
        for (EsmFeeRow row : rows) {
            if (row.rate() != null) {
                rowByNames.putIfAbsent(names(row.major(), row.middle()), row);
            }
        }
        Map<String, String[]> gmarketPath = gmarketPath(key);

        Set<EsmFeeRow> used = new LinkedHashSet<>();
        List<EsmFeeImportResult.Leaf> emptyLeaves = new ArrayList<>();
        int updated = 0;
        for (PlatformCategory leaf : leaves) {
            String gmarketCode = esmCategories.matchedLeaves(key, leaf.getCode()).gmarket();
            String[] path = gmarketCode == null ? null : gmarketPath.get(gmarketCode);
            EsmFeeRow row = null;
            if (path != null) {
                row = path[1] == null ? null : rowByNames.get(names(path[0], path[1]));
                if (row == null) {
                    row = rowByNames.get(names(path[0], WHOLE));
                }
            }
            if (row == null) {
                if (leaf.getCommissionRate() == null) {
                    emptyLeaves.add(new EsmFeeImportResult.Leaf(leaf.getCode(), leaf.getName()));
                }
                continue;
            }
            used.add(row);
            BigDecimal stored = toStoredRate(row.rate());
            platformCategoryRepository.save(leaf.toBuilder()
                    .commissionRate(stored)
                    .auctionCommissionRate(stored)
                    .build());
            updated++;
        }

        List<EsmFeeImportResult.Row> unused = new ArrayList<>();
        for (EsmFeeRow row : rows) {
            if (!used.contains(row)) {
                unused.add(new EsmFeeImportResult.Row(row.major(), row.middle(), row.rawRate()));
            }
        }
        return EsmFeeImportResult.builder()
                .leavesUpdated(updated)
                .unusedRows(unused)
                .emptyLeaves(emptyLeaves)
                .build();
    }

    /** D62: table value (VAT included) ÷ (1 + fee VAT rate), HALF_UP to 4 places, rounded once. */
    BigDecimal toStoredRate(BigDecimal tableRate) {
        return tableRate.divide(BigDecimal.ONE.add(feeVatRate), 4, RoundingMode.HALF_UP);
    }

    /** D67 ①: Gmarket leaf code → {major name, middle name (null when the top level is the leaf)}. */
    private Map<String, String[]> gmarketPath(EsmKey key) {
        Map<String, String[]> paths = new HashMap<>();
        for (EsmCategories.SiteCategory top : esmCategories.gmarketCategory(key, null).children()) {
            if (top.leaf()) {
                paths.put(top.code(), new String[]{top.name(), null});
                continue;
            }
            for (EsmCategories.SiteCategory middle : esmCategories.gmarketCategory(key, top.code()).children()) {
                String[] path = {top.name(), middle.name()};
                Deque<EsmCategories.SiteCategory> stack = new ArrayDeque<>();
                stack.push(middle);
                while (!stack.isEmpty()) {
                    EsmCategories.SiteCategory node = stack.pop();
                    if (node.leaf()) {
                        paths.put(node.code(), path);
                    } else {
                        esmCategories.gmarketCategory(key, node.code()).children().forEach(stack::push);
                    }
                }
            }
        }
        return paths;
    }

    private MarketplaceAccount findAccount(Long accountId) {
        return marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
    }

    private static String names(String major, String middle) {
        return strip(major) + "|" + strip(middle);
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String parentKey(PlatformCategory parent, String name) {
        return (parent == null ? "0" : String.valueOf(parent.getId())) + "|" + name;
    }

    private record Pending(PlatformCategory parent, String sdCode) {
    }
}
