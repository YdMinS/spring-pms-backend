package com.pms.service;

import com.pms.dto.request.CommissionRateRequest;
import com.pms.dto.response.CommissionRateResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Service interface for Commission Rate CRUD operations.
 *
 * Provides methods for managing commission rates with platform + category fallback support.
 * All methods are called from CommissionRateController (ADMIN-only endpoints).
 *
 * @see CommissionRateServiceImpl
 * @see CommissionRateController
 */
public interface CommissionRateService {
    /**
     * Creates a new commission rate from request data.
     *
     * @param request CommissionRateRequest with platform, categoryId (nullable), rate
     * @return CommissionRateResponse with assigned id
     */
    CommissionRateResponse create(CommissionRateRequest request);

    /**
     * Retrieves all commission rates.
     *
     * @return List of CommissionRateResponse objects (may be empty)
     */
    List<CommissionRateResponse> findAll();

    /**
     * Retrieves a single commission rate by ID.
     *
     * @param id Commission rate ID
     * @return CommissionRateResponse if found
     * @throws com.pms.exception.ResourceNotFoundException if id not found
     */
    CommissionRateResponse findById(Long id);

    /**
     * Updates an existing commission rate with new values.
     *
     * @param id Commission rate ID to update
     * @param request New CommissionRateRequest data
     * @return Updated CommissionRateResponse
     * @throws com.pms.exception.ResourceNotFoundException if id not found
     */
    CommissionRateResponse update(Long id, CommissionRateRequest request);

    /**
     * Deletes a commission rate by ID.
     *
     * @param id Commission rate ID to delete
     * @throws com.pms.exception.ResourceNotFoundException if id not found
     */
    void delete(Long id);

    /**
     * Finds applicable commission rate with fallback logic.
     *
     * Fallback priority:
     * 1. Platform + categoryId match (category-specific rate)
     * 2. Platform + categoryId=null (platform default rate)
     *
     * Example:
     * - findRate("COUPANG", 5) → returns 0.05 (COUPANG category 5 rate)
     * - If not found, falls back to findRate("COUPANG", null) → returns 0.03 (COUPANG default)
     *
     * @param platform Platform name
     * @param categoryId Category ID (nullable for platform default)
     * @return BigDecimal commission rate
     * @throws IllegalArgumentException if no rate found for platform
     */
    BigDecimal findRate(String platform, Long categoryId);

    /**
     * Retrieves all commission rates for a specific platform.
     *
     * @param platform Platform name (e.g., "COUPANG", "SMARTSTORE")
     * @return List of commission rates for the platform
     */
    List<CommissionRateResponse> getCommissionRatesByPlatform(String platform);
}
