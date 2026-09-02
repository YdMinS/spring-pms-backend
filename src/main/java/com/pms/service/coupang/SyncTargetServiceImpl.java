package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SyncStatus;
import com.pms.dto.response.SyncTargetResponse;
import com.pms.repository.MarketplaceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link SyncTargetService} 구현.
 *
 * 대상 필터는 {@link OrderSyncFacadeImpl} 의 syncEach 와 <b>동일 기준</b>(활성 + COUPANG)이다 —
 * 여기서 나오지 않은 계정은 동기화되지도 않으므로 진행률 분모가 실제와 일치한다.
 *
 * 매핑은 자격증명을 제외한 필드만 옮긴다({@link SyncTargetResponse} 주석 참고).
 * seller 는 두 finder 모두 {@code @EntityGraph} 로 eager fetch 하므로 LazyInit 위험이 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SyncTargetServiceImpl implements SyncTargetService {

    private static final String PLATFORM_COUPANG = "COUPANG";

    private final MarketplaceAccountRepository marketplaceAccountRepository;

    @Override
    public List<SyncTargetResponse> list(Long sellerId) {
        List<MarketplaceAccount> accounts = (sellerId == null)
                ? marketplaceAccountRepository.findByIsActiveTrue()
                : marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId);

        return accounts.stream()
                .filter(account -> PLATFORM_COUPANG.equals(account.getPlatform()))
                .map(this::toResponse)
                .toList();
    }

    private SyncTargetResponse toResponse(MarketplaceAccount account) {
        SyncStatus status = account.getLastSyncStatus();
        return SyncTargetResponse.builder()
                .accountId(account.getId())
                .sellerId(account.getSeller() == null ? null : account.getSeller().getId())
                .sellerName(account.getSeller() == null ? null : account.getSeller().getSellerName())
                .platform(account.getPlatform())
                .accountAlias(account.getAccountAlias())
                .lastSyncStatus(status == null ? null : status.name())
                .lastSyncAt(account.getLastSyncAt())
                .lastOrderSyncAt(account.getLastOrderSyncAt())
                .lastCancelSyncAt(account.getLastCancelSyncAt())
                .lastSyncError(account.getLastSyncError())
                .build();
    }
}
