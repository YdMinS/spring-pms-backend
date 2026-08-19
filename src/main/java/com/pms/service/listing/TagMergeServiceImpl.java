package com.pms.service.listing;

import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingTagRevision;
import com.pms.repository.ProductListingTagRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Tag combination + append-only revision recording (33). See {@link TagMergeService}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TagMergeServiceImpl implements TagMergeService {

    private final ProductListingTagRevisionRepository revisionRepository;

    @Override
    public List<String> resolveTags(ProductListing cell) {
        // Channel tags first (order preserved), then master tags appended without duplicates.
        List<String> merged = new ArrayList<>(dedup(cell.getTags()));
        List<String> masterTags = cell.getMasterProduct() != null
                ? dedup(cell.getMasterProduct().getTags())
                : List.of();
        for (String tag : masterTags) {
            if (!merged.contains(tag)) {
                merged.add(tag);
            }
        }
        int cap = cap(cell.getPlatform());
        return merged.size() > cap ? new ArrayList<>(merged.subList(0, cap)) : merged;
    }

    @Override
    public List<String> dedup(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                seen.add(tag);
            }
        }
        return new ArrayList<>(seen);
    }

    @Override
    public void recordRevisionIfChanged(ProductListing cell, List<String> merged) {
        List<String> latest = revisionRepository
                .findTopByProductListing_IdOrderByIdDesc(cell.getId())
                .map(ProductListingTagRevision::getTags)
                .orElse(null);
        // Order-sensitive equals; a null latest (no prior snapshot) counts as different → first push records.
        if (!merged.equals(latest)) {
            revisionRepository.save(ProductListingTagRevision.builder()
                    .productListing(cell)
                    .tags(merged)
                    .build());
        }
    }

    private int cap(String platform) {
        return TagLimits.BY_PLATFORM.getOrDefault(platform, TagLimits.DEFAULT);
    }
}
