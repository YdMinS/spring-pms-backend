package com.pms.service.elevenst;

import com.pms.domain.TenantElevenstSetting;
import com.pms.dto.request.ElevenstPhotoKeyRequest;
import com.pms.dto.response.ElevenstPhotoKeyResponse;
import com.pms.repository.TenantElevenstSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

/** {@link ElevenstPhotoKeyService} implementation — one {@link TenantElevenstSetting} row per tenant (D39). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ElevenstPhotoKeyServiceImpl implements ElevenstPhotoKeyService {

    private final TenantElevenstSettingRepository tenantElevenstSettingRepository;

    @Override
    public ElevenstPhotoKeyResponse get() {
        return new ElevenstPhotoKeyResponse(currentKey().isPresent());
    }

    @Override
    @Transactional
    public ElevenstPhotoKeyResponse update(ElevenstPhotoKeyRequest request) {
        String key = request == null || !StringUtils.hasText(request.apiKey()) ? null : request.apiKey().trim();
        TenantElevenstSetting next = tenantElevenstSettingRepository.findFirstByOrderByIdAsc()
                .map(s -> s.toBuilder().photoApiKey(key).build())
                .orElseGet(() -> TenantElevenstSetting.builder().photoApiKey(key).build());
        tenantElevenstSettingRepository.save(next);
        return new ElevenstPhotoKeyResponse(key != null);
    }

    @Override
    public Optional<String> currentKey() {
        return tenantElevenstSettingRepository.findFirstByOrderByIdAsc()
                .map(TenantElevenstSetting::getPhotoApiKey)
                .filter(StringUtils::hasText);
    }
}
