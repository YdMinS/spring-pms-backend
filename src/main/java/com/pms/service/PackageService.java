package com.pms.service;

import com.pms.domain.BoxKind;
import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Business operations for Package management.
 * CRUD operations + isDefault uniqueness maintenance.
 * For isDefault business logic details, see DOCS_A spec.
 */
public interface PackageService {

    /**
     * Create a new package. If isDefault=true, existing default is set to false.
     * @param request PackageRequest (type, cost, effectiveDate, isDefault)
     * @return PackageResponse with created package
     * @throws IllegalArgumentException if validation fails
     */
    PackageResponse createPackage(PackageRequest request);

    /**
     * Retrieve a single package by ID.
     * @param id package ID
     * @return PackageResponse
     * @throws com.pms.exception.ResourceNotFoundException if not found
     */
    PackageResponse getPackage(Long id);

    /**
     * Retrieve packages, optionally narrowed to one kind (FEATURE_2609_40 / PLAN D21).
     *
     * <p>🔴 Callers that feed the SELLING-PRICE calculation must pass {@link BoxKind#PURCHASED}: a recycled
     * box costs 0 and would price goods off a zero-cost box if it could be chosen as a default box. The
     * unfiltered call stays the default so the box-management and packing screens still see every box.</p>
     *
     * @param boxKind kind filter, or null for every kind
     * @return List<PackageResponse> (empty if none)
     */
    List<PackageResponse> getPackages(BoxKind boxKind);

    /** Every package, regardless of kind. */
    default List<PackageResponse> getPackages() {
        return getPackages(null);
    }

    /**
     * Update an existing package. If isDefault=true, existing default is set to false.
     * @param id package ID to update
     * @param request PackageRequest with updated values
     * @return PackageResponse with updated package
     * @throws com.pms.exception.ResourceNotFoundException if not found
     */
    PackageResponse updatePackage(Long id, PackageRequest request);

    /**
     * Delete a package by ID.
     * @param id package ID
     * @throws com.pms.exception.ResourceNotFoundException if not found
     */
    void deletePackage(Long id);

    /**
     * Upload a photo for a box and store its URL (FEATURE_2609_40 / PLAN D26).
     *
     * <p>Goes through the shared {@code ImageStorageService} seam — never a storage path of its own.
     * Clearing a photo only nulls the column; the stored object is deliberately kept.</p>
     *
     * @param id   package ID
     * @param file image file (validated by {@code ImageValidator})
     * @return PackageResponse with the new imageUrl
     * @throws com.pms.exception.ResourceNotFoundException if not found
     */
    PackageResponse uploadImage(Long id, MultipartFile file);
}
