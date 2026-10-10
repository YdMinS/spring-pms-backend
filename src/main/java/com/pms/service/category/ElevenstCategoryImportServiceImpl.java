package com.pms.service.category;

import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.response.ElevenstCategoryImportResult;
import com.pms.dto.response.ElevenstFeeImportResult;
import com.pms.repository.PlatformCategoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ElevenstCategoryImportService} implementation (FEATURE_2610_10).
 *
 * <p><b>Tree (D21 ① ② · D24)</b>: nodes are written parent first (sorted by depth). A leaf is matched by
 * (ELEVENST, code) and gets its name / parent refreshed; an intermediate keeps {@code code = null} and is
 * matched by (parent, name). Nothing is deleted and leaf commission is never touched here.</p>
 *
 * <p><b>Fees (D25 ~ D31)</b>: a table row finds its 11st category by the group rule (D27 ⑥: group 「해외직구」 →
 * the children of the top-level 「해외직구」, otherwise the top level) after the D30 name fixes; its exception
 * names are looked up only among that category's direct children (D27 ⑦). Every leaf under the category gets
 * the row value, then exception leaves get theirs (D27 ⑧). Stored value = percent ÷ 100 ÷ (1 + fee VAT rate),
 * rounded HALF_UP to 4 places once (D25). Leaves the table does not reach keep their value (D31).</p>
 *
 * <p>Fee-table names are compared with tree names after removing all whitespace.</p>
 */
@Service
@Transactional
public class ElevenstCategoryImportServiceImpl implements ElevenstCategoryImportService {

    static final String OVERSEAS_GROUP = "해외직구";
    static final String TREE_REQUIRED = "11번가 카테고리 목록을 먼저 들여와야 합니다.";

    /** D30 — table name → 11st top-level name. */
    static final Map<String, String> NAME_FIXES = Map.of(
            "캐쥬얼/유니섹스", "캐주얼/유니섹스",
            "세재/방향/살충", "세제/방향/살충",
            "전동/인라인/킥보드", "전동레저/인라인/킥보드",
            "여행/숙박/항공", "여행/숙박");

    /** D28 — 11st top-level name absent from the table → the table row whose fee it borrows. */
    static final String BORROWER = "구강/면도";
    static final String LENDER = "욕실용품";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ElevenstCategoryClient client;
    private final ElevenstCategoryXmlParser xmlParser;
    private final ElevenstFeeTableParser feeParser;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final BigDecimal feeVatRate;

    public ElevenstCategoryImportServiceImpl(ElevenstCategoryClient client,
                                             ElevenstCategoryXmlParser xmlParser,
                                             ElevenstFeeTableParser feeParser,
                                             PlatformCategoryRepository platformCategoryRepository,
                                             @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate) {
        this.client = client;
        this.xmlParser = xmlParser;
        this.feeParser = feeParser;
        this.platformCategoryRepository = platformCategoryRepository;
        this.feeVatRate = feeVatRate;
    }

    @Override
    public ElevenstCategoryImportResult importTree() {
        List<ElevenstCategoryNode> nodes = new ArrayList<>(xmlParser.parse(client.fetchCategoryXml()));
        nodes.sort(Comparator.comparingInt(ElevenstCategoryNode::depth));

        Map<String, PlatformCategory> leafByCode = new HashMap<>();
        Map<String, PlatformCategory> nodeByParentAndName = new HashMap<>();
        for (PlatformCategory pc : platformCategoryRepository.findByPlatform(Platform.ELEVENST)) {
            if (pc.getCode() != null) {
                leafByCode.put(pc.getCode(), pc);
            } else {
                nodeByParentAndName.put(parentKey(pc.getParent(), pc.getName()), pc);
            }
        }

        Map<String, PlatformCategory> byDispNo = new HashMap<>();
        int created = 0;
        int updated = 0;
        for (ElevenstCategoryNode node : nodes) {
            PlatformCategory parent = null;
            if (!"0".equals(node.parentDispNo())) {
                parent = byDispNo.get(node.parentDispNo());
                if (parent == null) {
                    throw new IllegalArgumentException("11번가 카테고리 응답에 부모가 없는 카테고리가 있습니다: " + node.dispNo());
                }
            }
            PlatformCategory saved;
            if (node.leaf()) {
                PlatformCategory existing = leafByCode.get(node.dispNo());
                if (existing != null) {
                    saved = platformCategoryRepository.save(existing.toBuilder()
                            .name(node.name()).parent(parent).build());
                    updated++;
                } else {
                    saved = platformCategoryRepository.save(PlatformCategory.builder()
                            .platform(Platform.ELEVENST).code(node.dispNo()).name(node.name())
                            .parent(parent).commissionRate(null).build());
                    created++;
                }
            } else {
                String key = parentKey(parent, node.name());
                saved = nodeByParentAndName.get(key);
                if (saved == null) {
                    saved = platformCategoryRepository.save(PlatformCategory.builder()
                            .platform(Platform.ELEVENST).code(null).name(node.name())
                            .parent(parent).commissionRate(null).build());
                    nodeByParentAndName.put(key, saved);
                    created++;
                }
            }
            byDispNo.put(node.dispNo(), saved);
        }

        return ElevenstCategoryImportResult.builder()
                .platformNodesCreated(created)
                .platformNodesUpdated(updated)
                .nodesProcessed(nodes.size())
                .build();
    }

    @Override
    public ElevenstFeeImportResult importFees(byte[] html) {
        List<ElevenstFeeRow> rows = feeParser.parse(html);

        List<PlatformCategory> all = platformCategoryRepository.findByPlatform(Platform.ELEVENST);
        if (all.isEmpty()) {
            throw new IllegalArgumentException(TREE_REQUIRED);
        }
        Map<Long, List<PlatformCategory>> children = new HashMap<>();
        List<PlatformCategory> roots = new ArrayList<>();
        for (PlatformCategory pc : all) {
            if (pc.getParent() == null) {
                roots.add(pc);
            } else {
                children.computeIfAbsent(pc.getParent().getId(), k -> new ArrayList<>()).add(pc);
            }
        }

        List<ElevenstFeeImportResult.Item> unmatchedCategories = new ArrayList<>();
        List<ElevenstFeeImportResult.Item> unmatchedExceptions = new ArrayList<>();
        List<ElevenstFeeImportResult.Borrowed> borrowed = new ArrayList<>();

        // D27 ⑥ — row → 11st category; D27 ④ / ⑨ — rows that cannot be placed are listed (table order), not saved.
        List<PlatformCategory> targets = new ArrayList<>(rows.size());
        Map<Long, Integer> rowsPerCategory = new HashMap<>();
        for (ElevenstFeeRow row : rows) {
            PlatformCategory target = row.feePercent() == null ? null : findCategory(row, roots, children);
            if (target != null && !hasLeaf(target, children)) {
                target = null;   // a node left empty by a re-import (renamed on 11st) places nothing
            }
            targets.add(target);
            if (target != null) {
                rowsPerCategory.merge(target.getId(), 1, Integer::sum);
            }
        }
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PlatformCategory target = targets.get(i);
            if (target == null || rowsPerCategory.get(target.getId()) > 1) {
                unmatchedCategories.add(rowItem(rows.get(i)));
            } else {
                placed.add(new Placed(rows.get(i), target));
            }
        }

        // D27 ⑧ — category value first, then exception values win.
        Map<Long, BigDecimal> rateByLeafId = new LinkedHashMap<>();
        Map<Long, PlatformCategory> leafById = new HashMap<>();
        for (Placed p : placed) {
            assign(p.target(), toStoredRate(p.row().feePercent()), children, rateByLeafId, leafById);
        }
        for (Placed p : placed) {
            ElevenstFeeRow row = p.row();
            for (ElevenstFeeRow.Excluded excluded : row.exclusions()) {
                PlatformCategory child = findOne(children.getOrDefault(p.target().getId(), List.of()),
                        excluded.name());
                if (child == null || !hasLeaf(child, children)) {
                    unmatchedExceptions.add(new ElevenstFeeImportResult.Item(row.group(), row.category(),
                            excluded.name(), excluded.percent() + "%"));
                    continue;
                }
                assign(child, toStoredRate(excluded.percent()), children, rateByLeafId, leafById);
            }
        }

        // D28 — 「구강/면도」 borrows 「욕실용품」 unless the table has its own row.
        boolean borrowerHasRow = rows.stream().anyMatch(r -> isDomesticRow(r, BORROWER));
        if (!borrowerHasRow) {
            ElevenstFeeRow lender = rows.stream()
                    .filter(r -> r.feePercent() != null && isDomesticRow(r, LENDER))
                    .findFirst().orElse(null);
            PlatformCategory borrower = findOne(roots, BORROWER);
            if (lender == null) {
                unmatchedCategories.add(new ElevenstFeeImportResult.Item(null, BORROWER, null, null));
            } else if (borrower != null) {
                assign(borrower, toStoredRate(lender.feePercent()), children, rateByLeafId, leafById);
                borrowed.add(new ElevenstFeeImportResult.Borrowed(BORROWER, LENDER, lender.feeText()));
            }
        }

        for (Map.Entry<Long, BigDecimal> e : rateByLeafId.entrySet()) {
            platformCategoryRepository.save(leafById.get(e.getKey()).toBuilder()
                    .commissionRate(e.getValue()).build());
        }

        return ElevenstFeeImportResult.builder()
                .leavesUpdated(rateByLeafId.size())
                .unmatchedCategories(unmatchedCategories)
                .unmatchedExceptions(unmatchedExceptions)
                .borrowed(borrowed)
                .build();
    }

    /** D27 ⑥ + D30. */
    private PlatformCategory findCategory(ElevenstFeeRow row, List<PlatformCategory> roots,
                                          Map<Long, List<PlatformCategory>> children) {
        String name = row.category();
        for (Map.Entry<String, String> fix : NAME_FIXES.entrySet()) {
            if (strip(fix.getKey()).equals(strip(name))) {
                name = fix.getValue();
            }
        }
        if (strip(row.group()).equals(OVERSEAS_GROUP)) {
            PlatformCategory overseas = findOne(roots, OVERSEAS_GROUP);
            return overseas == null ? null
                    : findOne(children.getOrDefault(overseas.getId(), List.of()), name);
        }
        return findOne(roots, name);
    }

    /** The one node whose name equals {@code name} ignoring whitespace; {@code null} for zero or several. */
    private static PlatformCategory findOne(List<PlatformCategory> candidates, String name) {
        String wanted = strip(name);
        List<PlatformCategory> hits = candidates.stream().filter(pc -> strip(pc.getName()).equals(wanted)).toList();
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** Writes {@code rate} for every leaf (code != null) at or under {@code top}. */
    private static void assign(PlatformCategory top, BigDecimal rate, Map<Long, List<PlatformCategory>> children,
                               Map<Long, BigDecimal> rateByLeafId, Map<Long, PlatformCategory> leafById) {
        Deque<PlatformCategory> stack = new ArrayDeque<>();
        stack.push(top);
        while (!stack.isEmpty()) {
            PlatformCategory pc = stack.pop();
            if (pc.getCode() != null) {
                rateByLeafId.put(pc.getId(), rate);
                leafById.put(pc.getId(), pc);
            }
            children.getOrDefault(pc.getId(), List.of()).forEach(stack::push);
        }
    }

    /** Whether any leaf (code != null) sits at or under {@code top}. */
    private static boolean hasLeaf(PlatformCategory top, Map<Long, List<PlatformCategory>> children) {
        Deque<PlatformCategory> stack = new ArrayDeque<>();
        stack.push(top);
        while (!stack.isEmpty()) {
            PlatformCategory pc = stack.pop();
            if (pc.getCode() != null) {
                return true;
            }
            children.getOrDefault(pc.getId(), List.of()).forEach(stack::push);
        }
        return false;
    }

    /** D25 — percent ÷ 100 ÷ (1 + fee VAT rate), HALF_UP to 4 places, rounded once. */
    BigDecimal toStoredRate(int percent) {
        return BigDecimal.valueOf(percent)
                .divide(HUNDRED.multiply(BigDecimal.ONE.add(feeVatRate)), 4, RoundingMode.HALF_UP);
    }

    /** A non-「해외직구」 row whose category text equals {@code name} ignoring whitespace. */
    private static boolean isDomesticRow(ElevenstFeeRow row, String name) {
        return !strip(row.group()).equals(OVERSEAS_GROUP) && strip(row.category()).equals(strip(name));
    }

    private static ElevenstFeeImportResult.Item rowItem(ElevenstFeeRow row) {
        return new ElevenstFeeImportResult.Item(row.group(), row.category(), null, row.feeText());
    }

    private static String parentKey(PlatformCategory parent, String name) {
        return (parent == null ? "0" : String.valueOf(parent.getId())) + "|" + name;
    }

    private static String strip(String s) {
        return ElevenstFeeTableParser.strip(s);
    }

    private record Placed(ElevenstFeeRow row, PlatformCategory target) {
    }
}
