package com.pms.service.price;

import com.pms.domain.Platform;
import com.pms.domain.PriceTargetType;
import com.pms.dto.response.PriceChangeView;
import com.pms.repository.PriceChangeLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link PriceHistoryService} implementation — one repository query, no per-combination finders.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PriceHistoryServiceImpl implements PriceHistoryService {

    /** Unfiltered call = "show me what happened lately", so it is capped instead of scanning the log. */
    private static final int DEFAULT_LIMIT = 100;

    /**
     * Hard cap for a filtered call. A log only grows, and one repriced option can produce thousands
     * of rows over a year — a screen must never try to render all of them at once.
     */
    private static final int MAX_LIMIT = 500;

    private final PriceChangeLogRepository priceChangeLogRepository;

    @Override
    public List<PriceChangeView> search(Long productId, Long optionId, Long listingId, Long masterProductId,
                                        Platform platform, PriceTargetType targetType,
                                        LocalDate from, LocalDate to) {
        boolean filtered = productId != null || optionId != null || listingId != null
                || masterProductId != null || platform != null || targetType != null;
        LocalDateTime start = (from == null) ? null : from.atStartOfDay();
        // Exclusive upper bound at the next midnight: `<= to.atStartOfDay()` would drop everything
        // that happened during the last day of the range.
        LocalDateTime end = (to == null) ? null : to.plusDays(1).atStartOfDay();
        return priceChangeLogRepository.search(productId, optionId, listingId, masterProductId,
                platform, targetType, start, end,
                PageRequest.of(0, filtered ? MAX_LIMIT : DEFAULT_LIMIT));
    }
}
