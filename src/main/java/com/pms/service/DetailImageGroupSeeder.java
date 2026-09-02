package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailImageGroup;
import com.pms.domain.DetailTemplate;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.repository.DetailImageGroupRepository;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Backfills the {@link DetailImageGroup} catalog from the zone ids already in use (FEATURE_2609_03), so
 * that turning on the catalog check in {@code DetailTemplateServiceImpl.validateBlocks} does not suddenly
 * reject templates that were saving fine yesterday.
 *
 * <p>Sources = every template's {@code imageZone} binds ∪ every master photo mapping's {@code zoneId},
 * minus the reserved cover-photo key {@link MasterImageZoneAssignment#SOURCE_ZONE} (not a zone). Names
 * start out equal to the code; giving them human-readable names is a rename from the UI.</p>
 *
 * <p>Runs {@code @Order(53)} — after {@link DefaultDetailTemplateSeeder} ({@code @Order(52)}) so the
 * default template's own zones are backfilled too. Idempotent: does nothing once the catalog is non-empty.
 * No {@code @Profile} — dev and prod both need it.</p>
 *
 * <p>⚠️ Seeds tenant 1 only, like {@link DefaultDetailTemplateSeeder}. A NEW tenant therefore starts with
 * an empty catalog, which makes every {@code imageZone} save a 400 until its catalog is populated —
 * handling that belongs to tenant bootstrap and is out of scope here.</p>
 */
@Slf4j
@Component
@Order(53)
@RequiredArgsConstructor
public class DetailImageGroupSeeder implements ApplicationRunner {

    private static final Long SEED_TENANT_ID = 1L;

    private final DetailImageGroupRepository detailImageGroupRepository;
    private final DetailTemplateRepository detailTemplateRepository;
    private final MasterImageZoneAssignmentRepository assignmentRepository;

    @Override
    public void run(ApplicationArguments args) {
        TenantContext.set(SEED_TENANT_ID);
        try {
            if (detailImageGroupRepository.count() > 0) {
                return;
            }
            // Sorted so sortOrder is deterministic across environments.
            TreeSet<String> codes = new TreeSet<>();
            for (DetailTemplate template : detailTemplateRepository.findAll()) {
                if (template.getBlocks() == null) {
                    continue;
                }
                for (DetailBlock block : template.getBlocks()) {
                    if ("imageZone".equals(block.getType()) && block.getBind() != null
                            && !block.getBind().isBlank()) {
                        codes.add(block.getBind());
                    }
                }
            }
            for (Object[] row : assignmentRepository.countByZoneIdGrouped()) {
                codes.add((String) row[0]);
            }
            codes.remove(MasterImageZoneAssignment.SOURCE_ZONE);
            if (codes.isEmpty()) {
                return;
            }
            List<DetailImageGroup> groups = new ArrayList<>();
            int sortOrder = 0;
            for (String code : codes) {
                groups.add(DetailImageGroup.builder()
                        .code(code)
                        .name(code)
                        .sortOrder(sortOrder++)
                        .build());
            }
            detailImageGroupRepository.saveAll(groups);
            log.info("Seeded {} detail image groups (tenant {})", groups.size(), SEED_TENANT_ID);
        } finally {
            TenantContext.clear();
        }
    }
}
