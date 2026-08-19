package com.pms.service.listing;

import com.pms.domain.ProductListing;

import java.util.List;

/**
 * Tag master+channel combination and push-time history (33).
 *
 * <p>Combine order: channel tags first (order preserved), then the master tags appended in order — a master
 * tag already present on the channel is skipped (dedup across the two sets); the result is truncated to the
 * platform cap ({@link TagLimits}). On a successful push the merged snapshot is appended as a
 * {@code ProductListingTagRevision} <b>only if it differs from the latest one</b>. The UI always reads the
 * current values, never the revisions.</p>
 */
public interface TagMergeService {

    /**
     * Merge a cell's channel tags with its master tags (channel-first, master appended without duplicates)
     * and truncate to the platform cap. Null tag sets are treated as empty.
     */
    List<String> resolveTags(ProductListing cell);

    /** Order-preserving de-duplication that drops null/blank entries (a set is deduped internally only). */
    List<String> dedup(List<String> tags);

    /**
     * Append a new {@code ProductListingTagRevision} for the cell iff {@code merged} differs (order included)
     * from the latest recorded snapshot — an absent latest counts as different, so the first push records.
     */
    void recordRevisionIfChanged(ProductListing cell, List<String> merged);
}
