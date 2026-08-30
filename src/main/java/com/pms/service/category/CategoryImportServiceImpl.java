package com.pms.service.category;

import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.PlatformCategory;
import com.pms.dto.response.CategoryImportResult;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PlatformCategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Coupang category xlsx import (FEATURE_2608_06 / 53). Single file = single transaction (all-or-nothing —
 * a parse/persist failure rolls back the whole file, never a partial seed). See {@link CategoryImportService}.
 *
 * <p>Per leaf, the path segments drive two homomorphic trees:</p>
 * <ul>
 *   <li><b>PlatformCategory (Coupang)</b> upsert: intermediates by (platform, parent, name), the leaf by
 *       (platform, code) with its name / commission / parent refreshed.</li>
 *   <li><b>oclyx Category mirror</b> (option A): intermediates by (parent, name); the leaf is judged
 *       <b>rename-safe</b> via the mapping's PlatformCategory FK — if a mapping already exists for this
 *       PlatformCategory, the mirror + mapping are left untouched (curation / renames preserved), so re-import
 *       never duplicates and never overwrites user edits.</li>
 * </ul>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CategoryImportServiceImpl implements CategoryImportService {

    private static final String PLATFORM = "COUPANG";
    private static final int FLUSH_EVERY = 500;

    private final CoupangCategoryXlsxParser parser;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryMappingRepository categoryMappingRepository;

    @PersistenceContext
    private EntityManager em;

    @Override
    public CategoryImportResult importCoupang(java.io.InputStream xlsx) {
        List<ParsedLeaf> leaves = parser.parse(xlsx);

        // Path caches (keyed by ">"-joined path prefix) so a shared intermediate node is resolved once per
        // import. Entities stay managed for the whole file — the context is flushed (not cleared) periodically
        // so cached parents remain valid as @ManyToOne targets for later rows.
        Map<String, PlatformCategory> platCache = new HashMap<>();
        Map<String, Category> oclyxCache = new HashMap<>();

        int platformNodesCreated = 0, platformNodesUpdated = 0;
        int oclyxNodesCreated = 0, mappingsCreated = 0, skipped = 0, leavesProcessed = 0;

        for (ParsedLeaf leaf : leaves) {
            leavesProcessed++;
            List<String> segments = leaf.segments();

            PlatformCategory parentPlat = null;
            Category parentOclyx = null;
            String pathPrefix = "";

            for (int i = 0; i < segments.size(); i++) {
                String name = segments.get(i);
                boolean isLeaf = (i == segments.size() - 1);
                pathPrefix = pathPrefix.isEmpty() ? name : pathPrefix + ">" + name;

                // ---- PlatformCategory (Coupang) node ----
                PlatformCategory platNode;
                if (isLeaf) {
                    Optional<PlatformCategory> existing =
                            platformCategoryRepository.findByPlatformAndCode(PLATFORM, leaf.code());
                    if (existing.isPresent()) {
                        platNode = platformCategoryRepository.save(existing.get().toBuilder()
                                .name(name).commissionRate(leaf.feeRate()).parent(parentPlat).build());
                        platformNodesUpdated++;
                    } else {
                        platNode = platformCategoryRepository.save(PlatformCategory.builder()
                                .platform(PLATFORM).code(leaf.code()).name(name)
                                .parent(parentPlat).commissionRate(leaf.feeRate()).build());
                        platformNodesCreated++;
                    }
                } else {
                    platNode = platCache.get(pathPrefix);
                    if (platNode == null) {
                        platNode = platformCategoryRepository
                                .findByPlatformAndParentAndName(PLATFORM, parentPlat, name)
                                .orElse(null);
                        if (platNode == null) {
                            platNode = platformCategoryRepository.save(PlatformCategory.builder()
                                    .platform(PLATFORM).code(null).name(name)
                                    .parent(parentPlat).commissionRate(null).build());
                            platformNodesCreated++;
                        }
                        platCache.put(pathPrefix, platNode);
                    }
                }

                // ---- oclyx Category mirror node ----
                Category oclyxNode;
                if (isLeaf) {
                    Optional<CategoryMapping> mapping =
                            categoryMappingRepository.findByPlatformCategoryId(platNode.getId());
                    if (mapping.isPresent()) {
                        // Mirror + mapping already exist (rename-safe): preserve them, do not recreate.
                        skipped++;
                    } else {
                        oclyxNode = categoryRepository.save(
                                Category.builder().name(name).parent(parentOclyx).build());
                        oclyxNodesCreated++;
                        categoryMappingRepository.save(CategoryMapping.builder()
                                .category(oclyxNode)
                                .platform(PLATFORM)
                                .platformCategoryId(platNode.getCode()) // legacy String col: the mall code
                                .platformCategoryName(truncate(pathPrefix, 255))
                                .platformCategory(platNode)             // FK (new logic resolves through this)
                                .build());
                        mappingsCreated++;
                    }
                    // leaf is terminal — no need to descend further
                } else {
                    oclyxNode = oclyxCache.get(pathPrefix);
                    if (oclyxNode == null) {
                        oclyxNode = findIntermediateMirror(parentOclyx, name);
                        if (oclyxNode == null) {
                            oclyxNode = categoryRepository.save(
                                    Category.builder().name(name).parent(parentOclyx).build());
                            oclyxNodesCreated++;
                        }
                        oclyxCache.put(pathPrefix, oclyxNode);
                    }
                    parentOclyx = oclyxNode;
                }

                parentPlat = platNode;
            }

            if (leavesProcessed % FLUSH_EVERY == 0) {
                em.flush(); // bound the pending-insert queue; no clear (cached parents must stay managed)
            }
        }

        return CategoryImportResult.builder()
                .platformNodesCreated(platformNodesCreated)
                .platformNodesUpdated(platformNodesUpdated)
                .oclyxNodesCreated(oclyxNodesCreated)
                .mappingsCreated(mappingsCreated)
                .leavesProcessed(leavesProcessed)
                .skipped(skipped)
                .build();
    }

    /** Match an existing intermediate oclyx node by (parent, name); root = parent-null filtered by name. */
    private Category findIntermediateMirror(Category parentOclyx, String name) {
        if (parentOclyx == null) {
            return categoryRepository.findByParentIsNull().stream()
                    .filter(c -> name.equals(c.getName()))
                    .findFirst().orElse(null);
        }
        return categoryRepository.findByParentIdAndName(parentOclyx.getId(), name).orElse(null);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
