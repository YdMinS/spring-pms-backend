package com.pms.service.sales;

import com.pms.domain.FixedCostChargeMode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.Platform;
import com.pms.domain.PlatformFixedCost;
import com.pms.dto.request.AccountFixedCostReplaceRequest;
import com.pms.dto.request.PlatformFixedCostPatchRequest;
import com.pms.dto.request.PlatformFixedCostRequest;
import com.pms.dto.response.AccountFixedCostResponse;
import com.pms.dto.response.PlatformFixedCostResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountFixedCostRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformFixedCostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 고정비 카탈로그 CRUD + 채널 연결 (FEATURE_2609_33 / PLAN 2609_33 D1 · D2 · D2-1 · D5).
 *
 * <p>테넌트 스코프는 {@code @TenantId} 가 자동으로 건다 — 수동 tenant 조건을 넣지 말 것.
 *
 * <p>🔴 카탈로그 항목의 <b>삭제는 연결이 없을 때만</b>이다(409). 쓰이는 항목을 지우면 그 채널의 과거
 * 순이익 계산 근거가 통째로 사라진다 — 그만 부과하고 싶은 것이라면 {@code active = false} 가 답이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FixedCostServiceImpl implements FixedCostService {

    /** 적용 구간의 단위는 <b>달</b>이다 — {@code YYYY-MM} 이 아닌 값은 400. */
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final PlatformFixedCostRepository platformFixedCostRepository;
    private final MarketplaceAccountFixedCostRepository accountFixedCostRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;

    // ── 카탈로그 ──────────────────────────────────────────────────────────

    @Override
    public List<PlatformFixedCostResponse> list() {
        // 비활성 항목도 내려보낸다 — 화면에서 다시 켤 수 있어야 한다.
        return platformFixedCostRepository.findAllByOrderByPlatformAscNameAsc().stream()
                .map(FixedCostServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public PlatformFixedCostResponse create(PlatformFixedCostRequest request) {
        Platform platform = Platform.from(request.platform());
        String name = request.name().trim();
        if (platformFixedCostRepository.existsByPlatformAndName(platform, name)) {
            throw new BusinessException("이미 있는 고정비 항목입니다: " + name, HttpStatus.CONFLICT);
        }
        PlatformFixedCost saved = platformFixedCostRepository.save(PlatformFixedCost.builder()
                .platform(platform)
                .name(name)
                .amount(request.amount())
                .thresholdAmount(request.thresholdAmount() == null
                        ? PlatformFixedCost.DEFAULT_THRESHOLD : request.thresholdAmount())
                .active(true)
                .build());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public PlatformFixedCostResponse update(Long id, PlatformFixedCostPatchRequest request) {
        PlatformFixedCost item = findItemOrThrow(id);
        // PATCH 관례: null 필드는 기존 값 유지.
        String name = request.name() == null ? item.getName() : request.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("항목명이 비어 있습니다");
        }
        // 자기 자신 제외 — 이름을 바꾸지 않고 저장하는 것이 자기 이름 때문에 409 가 되면 안 된다.
        if (platformFixedCostRepository.existsByPlatformAndNameAndIdNot(item.getPlatform(), name, id)) {
            throw new BusinessException("이미 있는 고정비 항목입니다: " + name, HttpStatus.CONFLICT);
        }
        PlatformFixedCost saved = platformFixedCostRepository.save(item.toBuilder()
                .name(name)
                .amount(request.amount() == null ? item.getAmount() : request.amount())
                .thresholdAmount(request.thresholdAmount() == null
                        ? item.getThresholdAmount() : request.thresholdAmount())
                .active(request.active() == null ? item.getActive() : request.active())
                .build());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        PlatformFixedCost item = findItemOrThrow(id);
        if (accountFixedCostRepository.existsByPlatformFixedCost_Id(id)) {
            throw new BusinessException("채널에 연결된 항목은 삭제할 수 없습니다 (끄려면 active=false)",
                    HttpStatus.CONFLICT);
        }
        platformFixedCostRepository.delete(item);
    }

    // ── 채널 연결 ─────────────────────────────────────────────────────────

    @Override
    public List<AccountFixedCostResponse> listForAccount(Long accountId) {
        findAccountOrThrow(accountId);
        return accountFixedCostRepository.findByMarketplaceAccount_Id(accountId).stream()
                .map(FixedCostServiceImpl::toResponse)
                .toList();
    }

    /**
     * 멱등 replace — 보낸 목록이 그 채널의 전부다.
     *
     * <p>🔴 {@code platform} 검사가 여기 있는 이유: 카탈로그의 축이 플랫폼이라 다른 플랫폼 항목이
     * 채널에 걸리면 카탈로그 목록(플랫폼별)과 매출 집계(채널별)가 서로 다른 답을 낸다.
     *
     * <p>⚠️ delete 뒤 {@code flush()} 가 필요하다 — 같은 트랜잭션 안에서 지운 행과 새로 넣는 행이
     * 유니크 키({@code account, item})가 같으면 flush 순서 때문에 제약 위반이 난다.
     */
    @Override
    @Transactional
    public List<AccountFixedCostResponse> replaceForAccount(Long accountId,
                                                            AccountFixedCostReplaceRequest request) {
        MarketplaceAccount account = findAccountOrThrow(accountId);
        List<AccountFixedCostReplaceRequest.Item> items =
                request == null || request.items() == null ? List.of() : request.items();

        Set<Long> ids = new LinkedHashSet<>();
        for (AccountFixedCostReplaceRequest.Item item : items) {
            if (!ids.add(item.fixedCostId())) {
                throw new IllegalArgumentException("같은 항목이 두 번 들어 있습니다: " + item.fixedCostId());
            }
        }
        Map<Long, PlatformFixedCost> catalog = new HashMap<>();
        platformFixedCostRepository.findAllById(ids).forEach(item -> catalog.put(item.getId(), item));

        List<MarketplaceAccountFixedCost> toSave = new ArrayList<>(items.size());
        for (AccountFixedCostReplaceRequest.Item item : items) {
            PlatformFixedCost catalogItem = catalog.get(item.fixedCostId());
            if (catalogItem == null) {
                throw new ResourceNotFoundException("고정비 항목", item.fixedCostId());
            }
            if (catalogItem.getPlatform() != account.getPlatform()) {
                throw new IllegalArgumentException("채널의 플랫폼과 다른 고정비 항목입니다: " + catalogItem.getName());
            }
            String appliedFrom = month(item.appliedFrom(), "적용 시작월");
            String appliedTo = month(item.appliedTo(), "적용 종료월");
            if (appliedFrom != null && appliedTo != null && appliedFrom.compareTo(appliedTo) > 0) {
                throw new IllegalArgumentException("적용 시작월이 종료월보다 늦습니다");
            }
            toSave.add(MarketplaceAccountFixedCost.builder()
                    .marketplaceAccount(account)
                    .platformFixedCost(catalogItem)
                    .chargeMode(chargeMode(item.chargeMode()))
                    .thresholdOverride(item.thresholdOverride())
                    .appliedFrom(appliedFrom)
                    .appliedTo(appliedTo)
                    .build());
        }

        accountFixedCostRepository.deleteByMarketplaceAccount_Id(accountId);
        accountFixedCostRepository.flush();
        accountFixedCostRepository.saveAll(toSave);
        return toSave.stream().map(FixedCostServiceImpl::toResponse).toList();
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private PlatformFixedCost findItemOrThrow(Long id) {
        return platformFixedCostRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("고정비 항목", id));
    }

    private MarketplaceAccount findAccountOrThrow(Long accountId) {
        return marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("판매채널", accountId));
    }

    private static FixedCostChargeMode chargeMode(String raw) {
        try {
            return FixedCostChargeMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 부과 방식: " + raw);
        }
    }

    private static String month(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (!MONTH.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(label + " 형식이 잘못됐습니다 (YYYY-MM): " + raw);
        }
        return trimmed;
    }

    private static PlatformFixedCostResponse toResponse(PlatformFixedCost item) {
        return new PlatformFixedCostResponse(item.getId(), item.getPlatform().name(), item.getName(),
                item.getAmount(), item.getThresholdAmount(), Boolean.TRUE.equals(item.getActive()));
    }

    private static AccountFixedCostResponse toResponse(MarketplaceAccountFixedCost link) {
        PlatformFixedCost item = link.getPlatformFixedCost();
        BigDecimal effective = link.effectiveThreshold();
        return new AccountFixedCostResponse(item.getId(), item.getName(), item.getAmount(),
                link.getChargeMode().name(), effective, link.getThresholdOverride(),
                link.getAppliedFrom(), link.getAppliedTo());
    }
}
