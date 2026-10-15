package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.repository.DetailTemplateRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Removes the A/S and return/exchange notice text blocks from every detail template on startup
 * (FEATURE_2610_16 / D13 · D23). Those texts now live on the 11st sales channel and are never drawn on the
 * detail page; {@code DetailTemplateServiceImpl} refuses such blocks on save (D24 ②).
 *
 * <p>Idempotent: a template without a notice block is not saved, so a second start changes nothing. Already
 * generated detail HTML is not regenerated (D24 ④).</p>
 *
 * <p>Runs {@code @Order(54)} — after {@link DetailImageGroupSeeder} ({@code @Order(53)}). No {@code @Profile}.
 * Iterates every tenant ({@code findDistinctTenantIds}, native) and sets {@link TenantContext} explicitly — at
 * boot there is no request, so a plain finder would read nothing.</p>
 */
@Slf4j
@Component
@Order(54)
@RequiredArgsConstructor
public class NoticeBlockCleanupRunner implements ApplicationRunner {

    private final DetailTemplateRepository detailTemplateRepository;

    @Override
    public void run(ApplicationArguments args) {
        for (Long tenantId : detailTemplateRepository.findDistinctTenantIds()) {
            try {
                TenantContext.set(tenantId);
                removeNoticeBlocks(tenantId);
            } finally {
                // ⚠️ thread reuse — a leaked tenant id is cross-tenant data exposure.
                TenantContext.clear();
            }
        }
    }

    private void removeNoticeBlocks(Long tenantId) {
        int removed = 0;
        for (DetailTemplate template : detailTemplateRepository.findAll()) {
            List<DetailBlock> blocks = template.getBlocks();
            if (blocks == null) {
                continue;
            }
            List<DetailBlock> kept = blocks.stream().filter(block -> !isNoticeBlock(block)).toList();
            if (kept.size() == blocks.size()) {
                continue;
            }
            detailTemplateRepository.save(template.toBuilder().blocks(kept).build());
            removed += blocks.size() - kept.size();
        }
        if (removed > 0) {
            log.info("Removed {} notice block(s) from detail templates (tenant {})", removed, tenantId);
        }
    }

    private static boolean isNoticeBlock(DetailBlock block) {
        return block != null && "text".equals(block.getType()) && block.getBind() != null
                && ListingTextValueResolver.NOTICE_KEYS.contains(block.getBind());
    }
}
