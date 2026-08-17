package com.pms.service;

import com.pms.dto.request.MarginPolicyRequest;
import com.pms.dto.response.MarginPolicyResponse;

import java.util.List;

/** CRUD for margin presets keyed by (seller, platform) — FEATURE_2608_06 / 3a. */
public interface MarginPolicyService {

    MarginPolicyResponse createMarginPolicy(MarginPolicyRequest request);

    MarginPolicyResponse getMarginPolicy(Long id);

    List<MarginPolicyResponse> getMarginPolicies();

    MarginPolicyResponse updateMarginPolicy(Long id, MarginPolicyRequest request);

    void deleteMarginPolicy(Long id);
}
