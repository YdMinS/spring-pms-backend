package com.pms.service;

import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;

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
     * Retrieve all packages.
     * @return List<PackageResponse> (empty if none)
     */
    List<PackageResponse> getPackages();

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
}
