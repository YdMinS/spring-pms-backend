package com.pms.service;

import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import com.pms.repository.PackageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PackageServiceImpl implements PackageService {

    private final PackageRepository packageRepository;

    @Override
    @Transactional
    public PackageResponse createPackage(PackageRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PackageResponse getPackage(Long id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PackageResponse> getPackages() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Transactional
    public PackageResponse updatePackage(Long id, PackageRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Transactional
    public void deletePackage(Long id) {
        throw new UnsupportedOperationException();
    }
}
