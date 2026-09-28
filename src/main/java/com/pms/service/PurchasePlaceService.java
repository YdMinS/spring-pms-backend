package com.pms.service;

import com.pms.dto.request.PurchasePlaceRequest;
import com.pms.dto.response.PurchasePlaceResponse;

import java.util.List;

/** Tenant-wide purchase place list (FEATURE_2609_76). Implementation: {@link PurchasePlaceServiceImpl}. */
public interface PurchasePlaceService {

    /** The tenant's list in display order; seeds the three defaults first when the list is empty (D19). */
    List<PurchasePlaceResponse> list();

    PurchasePlaceResponse create(PurchasePlaceRequest request);

    PurchasePlaceResponse rename(Long id, PurchasePlaceRequest request);

    /** Refused (400) while an active product uses the place (D9). */
    void delete(Long id);
}
