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
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ClaimActionServiceImpl — 가드(중복·상태)·감사기록·어댑터 해석.
 *
 * 어댑터는 실제 {@link CoupangClaimActionAdapter} 를 쓰되 그 안의 쿠팡 클라이언트만 목킹한다 —
 * 서비스가 어댑터 판정 결과에 어떻게 반응하는지가 검증 대상이라, 판정을 목으로 대체하면 화이트리스트
 * 가드가 실제로 도는지 알 수 없다. 어댑터 자체의 바디 조립은 CoupangClaimActionAdapterTest 담당.
 */
@ExtendWith(MockitoExtension.class)
class ClaimActionServiceImplTest {

    private static final String APPROVAL_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/returnRequests/{receiptId}/approval";

    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private OrderClaimActionRepository orderClaimActionRepository;
    @Mock private CoupangApiClient coupangApiClient;
    @Mock private com.pms.config.CoupangProperties coupangProperties;
    @Mock private com.pms.service.CarrierCodeService carrierCodeService;

    private ClaimActionServiceImpl service;

    @BeforeEach
    void setUp() {
        CoupangClaimActionAdapter adapter = new CoupangClaimActionAdapter(
                coupangApiClient, coupangProperties, carrierCodeService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        service = new ClaimActionServiceImpl(
                List.of(adapter), orderClaimRepository, orderClaimActionRepository);
        authenticateAs("ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void execute_unknownClaim_throwsNotFound() {
        given(orderClaimRepository.findWithAccountById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(999L, approveRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void execute_siblingAlreadySucceeded_throwsConflictWithoutCallingCoupang() {
        OrderClaim claim = claim(1L, "VENDOR_WAREHOUSE_CONFIRM", 1);
        givenClaimWithSiblings(claim, claim, claim(2L, "VENDOR_WAREHOUSE_CONFIRM", 2));
        given(orderClaimActionRepository.existsByOrderClaim_IdInAndActionAndSucceededTrue(
                anyList(), eq(ClaimAction.RETURN_APPROVE))).willReturn(true);

        assertThatThrownBy(() -> service.execute(1L, approveRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 처리된 접수입니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(coupangApiClient, never()).patch(anyString(), anyString(), any());
        verify(orderClaimActionRepository, never()).save(any());
    }

    @Test
    void execute_onlyFailedRecordsExist_resends() {
        OrderClaim claim = claim(1L, "VENDOR_WAREHOUSE_CONFIRM", 1);
        givenClaimWithSiblings(claim, claim);
        // 실패 기록만 있으면 exists(succeeded=true) 는 false 고, 성공 기록 조회도 비어 있다.
        given(orderClaimActionRepository.existsByOrderClaim_IdInAndActionAndSucceededTrue(
                anyList(), any())).willReturn(false);
        given(orderClaimActionRepository.findByOrderClaim_IdInAndSucceededTrue(anyList()))
                .willReturn(List.of());
        given(coupangProperties.getReturnApprovalPath()).willReturn(APPROVAL_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        ClaimActionResponse response = service.execute(1L, approveRequest());

        assertThat(response.succeeded()).isTrue();
        verify(coupangApiClient).patch(anyString(), anyString(), any());
    }

    @Test
    void execute_statusOutsideWhitelist_throwsBadRequestWithoutCallingCoupang() {
        // 반품완료 건에는 어떤 액션도 열리지 않는다(D2·D3).
        OrderClaim claim = claim(1L, "RETURNS_COMPLETED", 1);
        givenClaimWithSiblings(claim, claim);
        given(orderClaimActionRepository.existsByOrderClaim_IdInAndActionAndSucceededTrue(
                anyList(), any())).willReturn(false);
        given(orderClaimActionRepository.findByOrderClaim_IdInAndSucceededTrue(anyList()))
                .willReturn(List.of());

        assertThatThrownBy(() -> service.execute(1L, approveRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("현재 상태에서 실행할 수 없는 액션입니다");

        verify(coupangApiClient, never()).patch(anyString(), anyString(), any());
        verify(orderClaimActionRepository, never()).save(any());
    }

    @Test
    void execute_success_writesOneAuditRowWithStatusAtSend() {
        OrderClaim claim = claim(1L, "VENDOR_WAREHOUSE_CONFIRM", 1);
        givenClaimWithSiblings(claim, claim);
        givenNoPriorActions();
        given(coupangProperties.getReturnApprovalPath()).willReturn(APPROVAL_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any()))
                .willReturn("{\"code\":200,\"message\":\"OK\"}");

        service.execute(1L, approveRequest());

        ArgumentCaptor<OrderClaimAction> saved = ArgumentCaptor.forClass(OrderClaimAction.class);
        verify(orderClaimActionRepository).save(saved.capture());
        assertThat(saved.getValue().isSucceeded()).isTrue();
        assertThat(saved.getValue().getAction()).isEqualTo(ClaimAction.RETURN_APPROVE);
        // 사후에 "왜 이때 보냈나"를 설명하는 유일한 단서 — 전송 직전 원문 상태.
        assertThat(saved.getValue().getPlatformStatusAtSend()).isEqualTo("VENDOR_WAREHOUSE_CONFIRM");
        assertThat(saved.getValue().getResultCode()).isEqualTo("200");
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("admin@test.com");
        assertThat(saved.getValue().getRequestSummary()).contains("cancelCount=1");
    }

    @Test
    void execute_coupangBodyReportsFailure_recordsFailureAndThrows502() {
        OrderClaim claim = claim(1L, "VENDOR_WAREHOUSE_CONFIRM", 1);
        givenClaimWithSiblings(claim, claim);
        givenNoPriorActions();
        given(coupangProperties.getReturnApprovalPath()).willReturn(APPROVAL_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any()))
                .willReturn("{\"code\":400,\"message\":\"이미 처리된 반품입니다\"}");

        assertThatThrownBy(() -> service.execute(1L, approveRequest()))
                .isInstanceOf(ClaimActionFailedException.class);

        ArgumentCaptor<OrderClaimAction> saved = ArgumentCaptor.forClass(OrderClaimAction.class);
        verify(orderClaimActionRepository).save(saved.capture());
        assertThat(saved.getValue().isSucceeded()).isFalse();
        assertThat(saved.getValue().getResultMessage()).isEqualTo("이미 처리된 반품입니다");
    }

    @Test
    void execute_success_leavesLocalClaimStatusUntouched() {
        // D7 — 로컬 상태를 낙관적으로 바꾸지 않는다. platform_status 는 다음 동기화가 갱신한다.
        OrderClaim claim = claim(1L, "VENDOR_WAREHOUSE_CONFIRM", 1);
        givenClaimWithSiblings(claim, claim);
        givenNoPriorActions();
        given(coupangProperties.getReturnApprovalPath()).willReturn(APPROVAL_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        service.execute(1L, approveRequest());

        verify(orderClaimRepository, never()).save(any());
        verify(orderClaimRepository, never()).saveAll(any());
        assertThat(claim.getPlatformStatus()).isEqualTo("VENDOR_WAREHOUSE_CONFIRM");
    }

    @Test
    void availableActions_unsupportedPlatform_returnsEmptyListWithoutThrowing() {
        // D17 — 네이버 계정이 붙는 순간 클레임 화면이 500 으로 죽지 않아야 한다.
        OrderClaim naver = claim(1L, "RETURNS_UNCHECKED", 1).toBuilder().platform("NAVER").build();
        given(orderClaimRepository.findSiblingsBulk(eq(ClaimType.RETURN), anyList()))
                .willReturn(List.of(naver));
        given(orderClaimActionRepository.findByOrderClaim_IdInAndSucceededTrue(anyList()))
                .willReturn(List.of());

        Map<Long, List<ClaimActionOption>> actions = service.availableActions(List.of(naver));

        assertThat(actions.get(1L)).isEmpty();
    }

    @Test
    void execute_unsupportedPlatform_throwsBadRequestWithoutCallingCoupang() {
        OrderClaim naver = claim(1L, "RETURNS_UNCHECKED", 1).toBuilder().platform("NAVER").build();
        given(orderClaimRepository.findWithAccountById(1L)).willReturn(Optional.of(naver));

        assertThatThrownBy(() -> service.execute(1L, approveRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이 플랫폼은 아직 처리 액션을 지원하지 않습니다");

        verify(coupangApiClient, never()).patch(anyString(), anyString(), any());
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void availableActions_nonAdmin_returnsEmptyForEverything() {
        // 조회(GET /api/claims)는 인증만이라 USER 도 목록을 본다 — 액션은 ADMIN 전용이므로(D13)
        // 버튼을 실으면 누르고 403 을 받는다. 클라이언트가 분기하지 않게 서버가 비운다.
        authenticateAs("ROLE_USER");
        OrderClaim claim = claim(1L, "RETURNS_UNCHECKED", 1);

        Map<Long, List<ClaimActionOption>> actions = service.availableActions(List.of(claim));

        assertThat(actions).isEmpty();
        verify(orderClaimRepository, never()).findSiblingsBulk(any(), anyList());
    }

    @Test
    void availableActions_siblingSucceeded_hidesButtonOnEveryLine() {
        // 서버가 409 를 주기 전에 UI 에서 버튼이 사라지는 것이 정상 경로다(D6).
        OrderClaim first = claim(1L, "RETURNS_UNCHECKED", 1);
        OrderClaim second = claim(2L, "RETURNS_UNCHECKED", 2);
        given(orderClaimRepository.findSiblingsBulk(eq(ClaimType.RETURN), anyList()))
                .willReturn(List.of(first, second));
        given(orderClaimActionRepository.findByOrderClaim_IdInAndSucceededTrue(anyList()))
                .willReturn(List.of(OrderClaimAction.builder()
                        .orderClaim(second)                 // 형제 라인에 남은 성공 기록
                        .action(ClaimAction.RETURN_RECEIVE_CONFIRM)
                        .succeeded(true)
                        .build()));

        Map<Long, List<ClaimActionOption>> actions = service.availableActions(List.of(first, second));

        assertThat(actions.get(1L)).extracting(ClaimActionOption::action)
                .containsExactly(ClaimAction.RETURN_COLLECT_INVOICE);
        assertThat(actions.get(2L)).extracting(ClaimActionOption::action)
                .containsExactly(ClaimAction.RETURN_COLLECT_INVOICE);
    }

    @Test
    void availableActions_manyClaims_queriesTwiceRegardlessOfListSize() {
        // getClaims 는 페이지가 아니라 기간 전체 List 다 — claim 마다 조회하면 N+1 이 곧 조회 수가 된다.
        List<OrderClaim> claims = List.of(
                claim(1L, "RETURNS_UNCHECKED", 1),
                claim(2L, "RETURNS_UNCHECKED", 1),
                claim(3L, "VENDOR_WAREHOUSE_CONFIRM", 1));
        given(orderClaimRepository.findSiblingsBulk(eq(ClaimType.RETURN), anyList()))
                .willReturn(claims);
        given(orderClaimActionRepository.findByOrderClaim_IdInAndSucceededTrue(anyList()))
                .willReturn(List.of());

        service.availableActions(claims);

        verify(orderClaimRepository).findSiblingsBulk(any(), anyList());
        verify(orderClaimActionRepository).findByOrderClaim_IdInAndSucceededTrue(anyList());
        verify(orderClaimRepository, never()).findSiblings(any(), any(), anyString());
    }

    private void givenClaimWithSiblings(OrderClaim anchor, OrderClaim... siblings) {
        given(orderClaimRepository.findWithAccountById(anchor.getId())).willReturn(Optional.of(anchor));
        given(orderClaimRepository.findSiblings(1L, ClaimType.RETURN, "777"))
                .willReturn(List.of(siblings));
    }

    private void givenNoPriorActions() {
        given(orderClaimActionRepository.existsByOrderClaim_IdInAndActionAndSucceededTrue(
                anyList(), any())).willReturn(false);
        given(orderClaimActionRepository.findByOrderClaim_IdInAndSucceededTrue(anyList()))
                .willReturn(List.of());
    }

    private ClaimActionRequest approveRequest() {
        return new ClaimActionRequest(ClaimAction.RETURN_APPROVE, null, null, null, null);
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test.com", "n/a",
                        List.of(new SimpleGrantedAuthority(role))));
    }

    private OrderClaim claim(Long id, String platformStatus, int quantity) {
        MarketplaceAccount account = MarketplaceAccount.builder()
                .id(1L).platform("COUPANG").vendorId("A001").build();
        return OrderClaim.builder()
                .id(id)
                .marketplaceAccount(account)
                .platform("COUPANG")
                .claimType(ClaimType.RETURN)
                .externalClaimId("777")
                .externalOrderId("O-1")
                .externalItemId("V-" + id)
                .quantity(quantity)
                .platformStatus(platformStatus)
                .build();
    }
}
