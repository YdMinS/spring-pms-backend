package com.pms.service;

import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;

import java.util.List;

public interface PackageService {
    PackageResponse createPackage(PackageRequest request);
    PackageResponse getPackage(Long id);
    List<PackageResponse> getPackages();
    PackageResponse updatePackage(Long id, PackageRequest request);
    void deletePackage(Long id);
}
