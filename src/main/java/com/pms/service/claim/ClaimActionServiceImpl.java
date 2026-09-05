package com.pms.service.claim;

import com.pms.domain.ClaimAction;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderClaimAction;
import com.pms.dto.request.ClaimActionRequest;
import com.pms.dto.response.ClaimActionResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.OrderClaimActionRepository;
import com.pms.repository.OrderClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ClaimActionService} 구현 — 가드 → 어댑터 → 감사기록.
 *
 * <p>🔴 <b>클래스·메서드 어디에도 {@code @Transactional} 을 붙이지 않는다</b> — 외부 HTTP 를 도는
 * 경로다(발주처리 {@code OrderAcknowledgeServiceImpl} 과 같은 관례). 감사기록은
 * {@code SimpleJpaRepository.save} 자체 트랜잭션에서 짧게 커밋된다.
 *
 * <p>🔴 <b>{@code "COUPANG"} 같은 플랫폼 문자열이 이 클래스에 없어야 한다</b>(D17). 어댑터를
 * {@code platform()} 으로 고르는 것이 플랫폼 지식의 전부다 — 그래야 네이버가 클래스 1개 추가로 붙는다.
 *
 * <p>⚠️ 액션 성공 후 로컬 상태를 낙관적으로 바꾸지 않는다(D7). {@code order_claim.status}·
 * {@code platform_status} 는 다음 동기화가 갱신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimActionServiceImpl implements ClaimActionService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final List<ClaimActionAdapter> adapters;
    private final OrderClaimRepository orderClaimRepository;
    private final OrderClaimActionRepository orderClaimActionRepository;

    @Override
    public ClaimActionResponse execute(Long claimId, ClaimActionRequest request) {
        OrderClaim claim = orderClaimRepository.findWithAccountById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));

        ClaimActionAdapter adapter = resolve(claim.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException(
                        "이 플랫폼은 아직 처리 액션을 지원하지 않습니다"));

        ClaimAction action = request.action();
        MarketplaceAccount account = claim.getMarketplaceAccount();
        List<OrderClaim> siblings = siblingsOf(claim);
        List<Long> siblingIds = siblings.stream().map(OrderClaim::getId).toList();

        // 1) 중복 가드는 접수 단위다(D6) — 형제 라인 중 하나라도 성공했으면 재전송하지 않는다.
        //    실패 기록은 재시도를 막지 않는다.
        if (orderClaimActionRepository
                .existsByOrderClaim_IdInAndActionAndSucceededTrue(siblingIds, action)) {
            throw new BusinessException("이미 처리된 접수입니다", HttpStatus.CONFLICT);
        }

        // 2) 상태 화이트리스트(D3). 판정은 어댑터의 책임이고, 여기서는 그 결과에 없으면 거절만 한다.
        Set<ClaimAction> succeeded = succeededActions(siblingIds);
        boolean allowed = adapter.availableActions(claim, succeeded).stream()
                .anyMatch(option -> option.action() == action);
        if (!allowed) {
            throw new IllegalArgumentException("현재 상태에서 실행할 수 없는 액션입니다");
        }

        return send(adapter, claim, account, siblings, request);
    }

    /**
     * 전송 + 감사기록. 성공·실패·예외 <b>전부</b> 1행을 남긴다(D15).
     *
     * <p>{@code platform_status_at_send} 는 전송 <b>직전</b> 값을 확보한다 — 사후에 "왜 이때 보냈나"를
     * 설명하는 유일한 단서다.
     */
    private ClaimActionResponse send(ClaimActionAdapter adapter, OrderClaim claim,
                                     MarketplaceAccount account, List<OrderClaim> siblings,
                                     ClaimActionRequest request) {
        ClaimAction action = request.action();
        String statusAtSend = claim.getPlatformStatus();
        String summary = summarize(request, siblings);
        ClaimActionCommand command = new ClaimActionCommand(action, request.deliveryCompanyCode(),
                request.invoiceNumber(), request.regNumber(), request.rejectCode());

        ClaimActionOutcome outcome;
        try {
            outcome = adapter.execute(account, siblings, command);
        } catch (RuntimeException e) {
            // 예외로 빠져나가는 경로에도 기록이 남아야 한다 — 기록한 뒤 예외를 그대로 올린다.
            record(claim, action, false, statusAtSend, summary, "ERROR", e.getMessage());
            throw e;
        }
        record(claim, action, outcome.succeeded(), statusAtSend, summary,
                outcome.resultCode(), outcome.resultMessage());

        ClaimActionResponse response = new ClaimActionResponse(claim.getId(), action,
                outcome.succeeded(), outcome.resultCode(), outcome.resultMessage());
        if (!outcome.succeeded()) {
            throw new ClaimActionFailedException(response);
        }
        log.info("클레임 액션 성공: claim={} action={} receipt={} lines={}",
                claim.getId(), action, claim.getExternalClaimId(), siblings.size());
        return response;
    }

    @Override
    public Map<Long, List<ClaimActionOption>> availableActions(List<OrderClaim> claims) {
        if (claims.isEmpty() || !isAdmin()) {
            // 액션은 ADMIN 전용이라(D13) 조회만 가능한 사용자에게 버튼을 내리면 누르고 403 을 받는다.
            return Map.of();
        }

        // 1) 형제 라인 일괄. claim 마다 조회하면 목록 크기가 그대로 조회 수가 된다.
        Map<String, List<OrderClaim>> siblingsByKey = groupSiblings(claims);
        // 2) 성공 기록 일괄 — 형제 라인의 성공도 반영해야 버튼이 내려간다.
        List<Long> allIds = siblingsByKey.values().stream()
                .flatMap(List::stream).map(OrderClaim::getId).distinct().toList();
        Map<Long, Set<ClaimAction>> succeededById = succeededByClaimId(allIds);

        Map<Long, List<ClaimActionOption>> result = new LinkedHashMap<>();
        for (OrderClaim claim : claims) {
            Optional<ClaimActionAdapter> adapter = resolve(claim.getPlatform());
            if (adapter.isEmpty()) {
                result.put(claim.getId(), List.of());       // 미지원 플랫폼은 조용히 빈다(D17)
                continue;
            }
            Set<ClaimAction> succeeded = EnumSet.noneOf(ClaimAction.class);
            for (OrderClaim sibling : siblingsByKey.getOrDefault(siblingKey(claim), List.of(claim))) {
                succeeded.addAll(succeededById.getOrDefault(sibling.getId(), Set.of()));
            }
            result.put(claim.getId(), adapter.get().availableActions(claim, succeeded));
        }
        return result;
    }

    /**
     * 형제 라인 벌크 조회 → {@code (계정, 종류, 접수번호)} 키로 그룹핑.
     *
     * <p>조회는 {@code (종류, 접수번호 IN)} 으로 넓게 긁고 <b>계정은 여기서 갈린다</b> —
     * {@code externalClaimId} 는 계정 간 유일하지 않다.
     * <p>목록은 보통 단일 {@link ClaimType} 이라 쿼리는 1개다(종류가 섞이면 종류 수만큼).
     */
    private Map<String, List<OrderClaim>> groupSiblings(List<OrderClaim> claims) {
        Map<ClaimType, List<String>> claimIdsByType = new LinkedHashMap<>();
        for (OrderClaim claim : claims) {
            claimIdsByType.computeIfAbsent(claim.getClaimType(), k -> new ArrayList<>())
                    .add(claim.getExternalClaimId());
        }
        Map<String, List<OrderClaim>> byKey = new HashMap<>();
        for (Map.Entry<ClaimType, List<String>> entry : claimIdsByType.entrySet()) {
            List<String> ids = entry.getValue().stream().distinct().toList();
            for (OrderClaim sibling : orderClaimRepository.findSiblingsBulk(entry.getKey(), ids)) {
                byKey.computeIfAbsent(siblingKey(sibling), k -> new ArrayList<>()).add(sibling);
            }
        }
        return byKey;
    }

    /**
     * 단건 경로의 형제 조회 — 벌크판({@link #groupSiblings})의 {@code claimIds} 가 1개인 경우와
     * 같은 조건이어야 한다. 갈리면 목록의 버튼과 서버 409 가 어긋난다.
     */
    private List<OrderClaim> siblingsOf(OrderClaim claim) {
        List<OrderClaim> siblings = orderClaimRepository.findSiblings(
                claim.getMarketplaceAccount().getId(), claim.getClaimType(), claim.getExternalClaimId());
        // 조회에 자기 자신이 없을 수 없지만, 비면 클릭한 라인만으로 진행한다(합계·가드가 무의미해지지 않게).
        return siblings.isEmpty() ? List.of(claim) : siblings;
    }

    private String siblingKey(OrderClaim claim) {
        return claim.getMarketplaceAccount().getId() + "|" + claim.getClaimType()
                + "|" + claim.getExternalClaimId();
    }

    private Set<ClaimAction> succeededActions(List<Long> claimIds) {
        Set<ClaimAction> actions = EnumSet.noneOf(ClaimAction.class);
        succeededByClaimId(claimIds).values().forEach(actions::addAll);
        return actions;
    }

    private Map<Long, Set<ClaimAction>> succeededByClaimId(List<Long> claimIds) {
        Map<Long, Set<ClaimAction>> byClaim = new HashMap<>();
        if (claimIds.isEmpty()) {
            return byClaim;
        }
        for (OrderClaimAction record : orderClaimActionRepository
                .findByOrderClaim_IdInAndSucceededTrue(claimIds)) {
            // orderClaim 은 LAZY 프록시 — id 만 읽는다(초기화되지 않는다).
            byClaim.computeIfAbsent(record.getOrderClaim().getId(),
                    k -> EnumSet.noneOf(ClaimAction.class)).add(record.getAction());
        }
        return byClaim;
    }

    private Optional<ClaimActionAdapter> resolve(String platform) {
        return adapters.stream().filter(a -> a.platform().equals(platform)).findFirst();
    }

    /** {@code k=v} 를 {@code ,} 로 이은 한 줄. ⚠️ PII 금지 — 송장번호·거부코드·수량까지만. */
    private String summarize(ClaimActionRequest request, List<OrderClaim> siblings) {
        List<String> parts = new ArrayList<>();
        parts.add("lines=" + siblings.size());
        if (request.deliveryCompanyCode() != null && !request.deliveryCompanyCode().isBlank()) {
            parts.add("carrier=" + request.deliveryCompanyCode());
        }
        if (request.invoiceNumber() != null && !request.invoiceNumber().isBlank()) {
            parts.add("invoice=" + request.invoiceNumber());
        }
        if (request.rejectCode() != null && !request.rejectCode().isBlank()) {
            parts.add("rejectCode=" + request.rejectCode());
        }
        parts.add("cancelCount=" + siblings.stream()
                .mapToInt(c -> c.getQuantity() == null ? 0 : c.getQuantity()).sum());
        return String.join(",", parts);
    }

    private void record(OrderClaim claim, ClaimAction action, boolean succeeded,
                        String statusAtSend, String summary, String code, String message) {
        try {
            orderClaimActionRepository.save(OrderClaimAction.builder()
                    .orderClaim(claim)
                    .action(action)
                    .succeeded(succeeded)
                    .platformStatusAtSend(statusAtSend)
                    .requestSummary(summary)
                    .resultCode(code)
                    .resultMessage(truncate(message))
                    .createdBy(currentUsername())
                    .build());
        } catch (Exception e) {
            // 기록 실패가 전송 결과를 뒤집으면 안 된다 — 이미 보낸 것은 되돌릴 수 없다.
            log.error("클레임 액션 감사기록 실패: claim={} action={} succeeded={}",
                    claim.getId(), action, succeeded, e);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_ADMIN::equals);
    }
}
