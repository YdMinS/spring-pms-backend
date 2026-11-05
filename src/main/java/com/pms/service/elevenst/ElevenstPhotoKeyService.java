package com.pms.service.elevenst;

import com.pms.dto.request.ElevenstPhotoKeyRequest;
import com.pms.dto.response.ElevenstPhotoKeyResponse;

import java.util.Optional;

/**
 * The company's 11st photo lookup key (FEATURE_2611_11 / D39): one per tenant, shared by every 11st sales channel.
 *
 * <p>🔴 The key is read only by {@link #currentKey()} (the 11st photo read). Screens learn whether it is set, never the
 * key. A seller API key is never used in its place (D39 — it would travel unencrypted in the address).</p>
 */
public interface ElevenstPhotoKeyService {

    /** Whether the current tenant has a key. */
    ElevenstPhotoKeyResponse get();

    /** Store the key (trimmed); a blank one clears it. */
    ElevenstPhotoKeyResponse update(ElevenstPhotoKeyRequest request);

    /** The current tenant's key; empty when none is stored. */
    Optional<String> currentKey();
}
