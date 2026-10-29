package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.MasterApprovalRefreshResponse;
import com.pms.dto.response.MasterApprovalRefreshResponse.Outcome;
import com.pms.dto.response.MasterApprovalRefreshResponse.Result;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Master-wide approval refresh (FEATURE_2610_38). See {@link MasterApprovalRefreshService}.
 *
 * <p>⚠️ The class is intentionally NOT {@code @Transactional} (D8): every listing that is not skipped is refreshed
 * through {@link ListingRegistrationService#fetchStatus} — another bean, so its own {@code @Transactional} opens
 * and commits a transaction per refreshed listing. Never move this loop into {@code ListingRegistrationServiceImpl}
 * (its class default is a read-only transaction).</p>
 */
@Service
@RequiredArgsConstructor
public class MasterApprovalRefreshServiceImpl implements MasterApprovalRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MasterApprovalRefreshServiceImpl.class);

    /** D3: the statuses whose row menu shows [승인 새로고침]. */
    private static final Set<ListingStatus> REFRESHABLE_STATUSES =
            Set.of(ListingStatus.SUBMITTED, ListingStatus.SELLING, ListingStatus.REJECTED);
    /** D6: the markets with a listing adapter (Coupang · 11st · ESM); any other market is skipped. */
    private static final Set<Platform> REFRESHABLE_PLATFORMS =
            Set.of(Platform.COUPANG, Platform.ELEVENST, Platform.ESM);

    static final String NOT_REFRESHABLE_STATUS = "새로고침 대상 상태가 아님";
    static final String UNSUPPORTED_MARKET = "새로고침을 지원하지 않는 마켓";
    static final String RATE_LIMITED = "쿠팡 호출 한도 초과 — 잠시 뒤 다시 시도";
    /** A failure without a message shows the global handler's text ({@code GlobalExceptionHandler.handleException}). */
    static final String NO_MESSAGE = "Internal server error";

    private final MasterProductRepository masterProductRepository;
    private final ProductListingRepository productListingRepository;
    private final ListingRegistrationService listingRegistrationService;

    @Override
    public MasterApprovalRefreshResponse refresh(Long masterId, List<Long> listingIds) {
        // Tenant-scoped existence check (findScopedById is @TenantId-filtered → another tenant's or absent = 404).
        masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));

        Map<Long, ProductListing> cells = productListingRepository.findByMasterProductId(masterId).stream()
                .collect(Collectors.toMap(ProductListing::getId, Function.identity()));
        // D9: one id outside this master → 400 before any market call.
        List<Long> foreign = listingIds.stream().filter(id -> !cells.containsKey(id)).toList();
        if (!foreign.isEmpty()) {
            throw new IllegalArgumentException("이 마스터의 판매상품이 아닌 번호가 있습니다: " + foreign);
        }

        Set<String> rateLimitedAccounts = new HashSet<>();
        List<Result> results = new ArrayList<>();
        int refreshed = 0, skipped = 0, failed = 0;
        for (Long id : listingIds) {
            ProductListing cell = cells.get(id);
            String platform = cell.getPlatform().name();
            // getSeller().getId() reads the FK id only — the LAZY proxy stays asleep outside a transaction.
            String account = cell.getSeller().getId() + "|" + platform;
            // Skip without a market call: status changed since the pick (D9) → no adapter (D6) → 429 earlier (D7).
            String skipReason = !REFRESHABLE_STATUSES.contains(cell.getStatus()) ? NOT_REFRESHABLE_STATUS
                    : !REFRESHABLE_PLATFORMS.contains(cell.getPlatform()) ? UNSUPPORTED_MARKET
                    : rateLimitedAccounts.contains(account) ? RATE_LIMITED
                    : null;
            if (skipReason != null) {
                skipped++;
                results.add(line(cell, Outcome.SKIPPED).message(skipReason).build());
                continue;
            }
            try {
                ListingStatusResponse res = listingRegistrationService.fetchStatus(id);   // proxy → own transaction
                refreshed++;
                results.add(line(cell, Outcome.REFRESHED)
                        .status(res.getStatus())
                        .reviewNote(res.getReviewNote())
                        .reviewNoteState(res.getReviewNoteState())
                        .build());
            } catch (Exception e) {
                if (e instanceof CoupangRateLimitedException) {
                    rateLimitedAccounts.add(account);   // D7: the rest of this seller × market is skipped
                }
                failed++;
                String message = e.getMessage() == null || e.getMessage().isBlank() ? NO_MESSAGE : e.getMessage();
                results.add(line(cell, Outcome.FAILED).message(message).build());
                log.warn("[MASTER-APPROVAL-REFRESH] masterId={} listingId={} failed: {}", masterId, id, message);
            }
        }
        return MasterApprovalRefreshResponse.builder()
                .requested(listingIds.size())
                .refreshed(refreshed)
                .skipped(skipped)
                .failed(failed)
                .results(results)
                .build();
    }

    private static Result.ResultBuilder line(ProductListing cell, Outcome outcome) {
        return Result.builder()
                .productListingId(cell.getId())
                .platform(cell.getPlatform().name())
                .outcome(outcome);
    }
}
