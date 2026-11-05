package com.pms.service;

/**
 * Copies a marketplace photo into our storage (FEATURE_2611_11 / D6 · D36) — the single place that does it for
 * [「마켓 기준으로 맞추기」] and the import. A push then sends the copy's address, never the market's.
 *
 * <p>The address comes from the market's own API answer (not from a person), so no host allowlist applies — unlike
 * {@code ProductImageServiceImpl.addImagesFromUrls}. It still goes out over {@code https} only, the format is decided by
 * the bytes (JPEG · PNG · GIF · WEBP) and the size cap is the upload one. Any failure throws
 * {@link IllegalArgumentException}.</p>
 */
public interface MarketImageStore {

    /**
     * @param marketUrl the market's absolute photo address ({@code http://} is raised to {@code https://})
     * @param listingId the listing the copy belongs to (file name only)
     * @param index     the photo's position in the copied set (file name only)
     * @return the stored copy's address (S3 public URL on dev/prod)
     */
    String copy(String marketUrl, Long listingId, int index);
}
