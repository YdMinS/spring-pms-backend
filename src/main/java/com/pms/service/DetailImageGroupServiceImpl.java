package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailImageGroup;
import com.pms.domain.DetailTemplate;
import com.pms.dto.request.DetailImageGroupRequest;
import com.pms.dto.response.DetailImageGroupResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.DetailImageGroupRepository;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detail image group catalog CRUD (FEATURE_2609_03). Tenant isolation is automatic via {@code @TenantId}
 * on {@link DetailImageGroup} — no manual tenant conditions.
 *
 * <p>⚠️ Entity is immutable (no setters): a rename rebuilds via {@code toBuilder}, and only the display
 * name changes — {@code code} is the mapping key and has no update path.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DetailImageGroupServiceImpl implements DetailImageGroupService {

    /** Number of template names carried in the delete-blocked reason. */
    private static final int MAX_USED_BY_NAMES = 5;

    /** Leaves room inside the VARCHAR(100) code column for a "_2"/"_3" uniqueness suffix. */
    private static final int MAX_SLUG_LENGTH = 90;

    private final DetailImageGroupRepository detailImageGroupRepository;
    private final DetailTemplateRepository detailTemplateRepository;
    private final MasterImageZoneAssignmentRepository assignmentRepository;

    @Override
    public List<DetailImageGroupResponse> list() {
        // Both maps are built once per call — a per-group lookup would be N+1.
        Map<String, List<String>> templateNames = templateNamesByCode();
        Map<String, Integer> imageCounts = imageCountsByZone();
        return detailImageGroupRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(group -> mapToResponse(group, templateNames, imageCounts))
                .toList();
    }

    @Override
    @Transactional
    public DetailImageGroupResponse create(DetailImageGroupRequest request) {
        String name = request.getName().trim();
        if (detailImageGroupRepository.existsByName(name)) {
            throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
        }
        List<DetailImageGroup> existing = detailImageGroupRepository.findAllByOrderBySortOrderAscIdAsc();
        int nextSortOrder = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSortOrder() + 1;
        DetailImageGroup saved = detailImageGroupRepository.save(DetailImageGroup.builder()
                .code(generateCode(name))
                .name(name)
                .sortOrder(nextSortOrder)
                .build());
        // A brand-new group is used by nothing yet, so the counts are known without querying.
        return mapToResponse(saved, Map.of(), Map.of());
    }

    @Override
    @Transactional
    public DetailImageGroupResponse rename(Long id, DetailImageGroupRequest request) {
        DetailImageGroup group = findOrThrow(id);
        String name = request.getName().trim();
        // Excluding self: saving without actually changing the name must not 400 on its own name.
        if (detailImageGroupRepository.existsByNameAndIdNot(name, id)) {
            throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
        }
        DetailImageGroup saved = detailImageGroupRepository.save(group.toBuilder().name(name).build());
        return mapToResponse(saved, templateNamesByCode(), imageCountsByZone());
    }

    /**
     * Delete a catalog group. Succeeds only while NO active template binds its code (otherwise 400) — an
     * in-use zone must be removed from every template first, or its blocks would fail validation on the
     * next save.
     *
     * <p>⚠️ Deletes the group row and the photo mappings carrying its code
     * ({@code master_image_zone_assignment}) — the photos themselves
     * ({@code master_product_image} / {@code product_image} / S3) are never touched. Only the
     * "group ↔ photo" link goes; the images stay in the master's pool as unused entries. A non-zero
     * {@code imageCount} therefore does NOT block this delete (the UI only warns with it).</p>
     */
    @Override
    @Transactional
    public void delete(Long id) {
        DetailImageGroup group = findOrThrow(id);
        List<String> usedBy = templateNamesByCode().getOrDefault(group.getCode(), List.of());
        if (!usedBy.isEmpty()) {
            throw new IllegalArgumentException("사용 중인 템플릿이 있습니다: " + String.join(", ", usedBy));
        }
        // Photo mappings are NOT a delete blocker: with no template referencing the zone they can never be
        // rendered again. Only the mapping rows go — the pool images stay in the master's pool as unused.
        assignmentRepository.deleteByZoneIdScoped(group.getCode());
        detailImageGroupRepository.delete(group);
    }

    /**
     * code → names of the ACTIVE templates whose blocks bind that zone.
     *
     * <p>⚠️ Inactive templates are deliberately not counted: template rows survive deactivation, and
     * counting them would make a group permanently undeletable because of a template nobody can see.</p>
     *
     * <p>⚠️ {@code blocks} is a JSON TEXT column, so this cannot be a SQL aggregate. Templates are a
     * single-digit-per-tenant library, so loading them all is the intended design.</p>
     */
    private Map<String, List<String>> templateNamesByCode() {
        Map<String, List<String>> namesByCode = new LinkedHashMap<>();
        for (DetailTemplate template : detailTemplateRepository.findAll()) {
            if (!Boolean.TRUE.equals(template.getActive()) || template.getBlocks() == null) {
                continue;
            }
            for (DetailBlock block : template.getBlocks()) {
                if (!"imageZone".equals(block.getType()) || block.getBind() == null) {
                    continue;
                }
                List<String> names = namesByCode.computeIfAbsent(block.getBind(), key -> new ArrayList<>());
                if (!names.contains(template.getName())) {
                    names.add(template.getName());
                }
            }
        }
        return namesByCode;
    }

    /** zoneId → mapped photo count, in one grouped query (includes {@code __source__}, which no group uses). */
    private Map<String, Integer> imageCountsByZone() {
        Map<String, Integer> counts = new HashMap<>();
        for (Object[] row : assignmentRepository.countByZoneIdGrouped()) {
            counts.put((String) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * Derive the immutable code from the display name: lowercase, every run of non-alphanumerics becomes
     * {@code _}, trimmed. A name with no ASCII alphanumerics (Korean, say) yields {@code zone_}+6 random
     * chars — unreadable but never shown to the user (the UI shows {@code name}); immutability wins over
     * readability here. Collisions get a {@code _2}, {@code _3} … suffix.
     */
    private String generateCode(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("_+$", "");
        }
        if (slug.isEmpty()) {
            slug = "zone_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        }
        String candidate = slug;
        int suffix = 2;
        while (detailImageGroupRepository.existsByCode(candidate)) {
            candidate = slug + "_" + suffix++;
        }
        return candidate;
    }

    private DetailImageGroup findOrThrow(Long id) {
        return detailImageGroupRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DetailImageGroup", id));
    }

    private DetailImageGroupResponse mapToResponse(DetailImageGroup group,
                                                   Map<String, List<String>> templateNames,
                                                   Map<String, Integer> imageCounts) {
        List<String> usedBy = templateNames.getOrDefault(group.getCode(), List.of());
        return DetailImageGroupResponse.builder()
                .id(group.getId())
                .code(group.getCode())
                .name(group.getName())
                .sortOrder(group.getSortOrder())
                .templateCount(usedBy.size())
                .imageCount(imageCounts.getOrDefault(group.getCode(), 0))
                .usedByTemplateNames(usedBy.stream().limit(MAX_USED_BY_NAMES).toList())
                .build();
    }
}
