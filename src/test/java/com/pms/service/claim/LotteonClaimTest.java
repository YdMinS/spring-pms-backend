package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.LotteonProperties;
import com.pms.domain.ClaimAction;
import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.CollectInvoiceSource;
import com.pms.domain.LotteonAccountCredential;
import com.pms.domain.LotteonClaim;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.dto.request.ClaimActionRequest;
import com.pms.dto.response.ClaimActionResponse;
import com.pms.exception.BusinessException;
import com.pms.repository.LotteonClaimRepository;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderClaimActionRepository;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.coupang.OrderSyncPreset;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonCancelRequestUpserter;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderApi.LineFailure;
import com.pms.service.lotteon.LotteonOrderSyncServiceImpl;
import com.pms.service.lotteon.LotteonOrderUpserter;
import com.pms.service.lotteon.LotteonPaths;
import com.pms.service.lotteon.LotteonSellerChoices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FEATURE_2611_23 / 07 — LotteON returns / exchanges: list load (R29 · R30 · D85 · D72), [140] rows (D67 · D68 · R32),
 * D86 order-number follow-up, the order-round claim step (R31 · R39), buttons (R35 · D73 · D80), sending (R33 ~ R36)
 * and the status note (D79).
 */
@ExtendWith(MockitoExtension.class)
class LotteonClaimTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private LotteonClaimRepository lotteonClaimRepository;
    @Mock private LotteonOrderLineRepository lotteonOrderLineRepository;
    @Mock private LotteonOrderApi lotteonOrderApi;
    @Mock private LotteonApiClient lotteonApiClient;
    @Mock private LotteonClaimUpserter lotteonClaimUpserter;
    @Mock private ClaimStaleSweeper claimStaleSweeper;
    @Mock private SyncStatusRecorder syncStatusRecorder;
    @Mock private CarrierCodeService carrierCodeService;
    @Mock private LotteonOrderUpserter lotteonOrderUpserter;
    @Mock private LotteonCancelRequestUpserter lotteonCancelRequestUpserter;
    @Mock private LotteonClaimSyncAdapter lotteonClaimSyncAdapter;
    @Mock private OrderClaimActionRepository orderClaimActionRepository;
    @Mock private ClaimCollectInvoiceRecorder collectInvoiceRecorder;
    @Mock private ClaimActionAdapter claimActionAdapter;

    private final MarketplaceAccount account = MarketplaceAccount.builder().id(9L).platform(Platform.LOTTEON)
            .lotteonCredential(LotteonAccountCredential.builder().apiKey("KEY").build())
            .build();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- fixtures

    private LotteonClaimUpserter upserter() {
        return new LotteonClaimUpserter(orderClaimRepository, lotteonClaimRepository, lotteonOrderLineRepository);
    }

    private LotteonClaimSyncAdapter syncAdapter(LotteonClaimUpserter upserter) {
        return new LotteonClaimSyncAdapter(lotteonOrderApi, upserter, orderClaimRepository, claimStaleSweeper,
                syncStatusRecorder, new LotteonProperties());
    }

    private LotteonClaimActionAdapter actionAdapter(LotteonOrderApi api) {
        return new LotteonClaimActionAdapter(api, lotteonClaimRepository, lotteonClaimUpserter, carrierCodeService);
    }

    private OrderClaim returnClaim(ClaimStatus status, String step, String invoice) {
        return OrderClaim.builder().id(31L).marketplaceAccount(account).platform(Platform.LOTTEON)
                .claimType(ClaimType.RETURN).externalClaimId("C1").externalOrderId("OD1").externalItemId("1")
                .quantity(1).status(status).platformStatus(step)
                .collectInvoiceNo(invoice).collectCarrierCode(invoice == null ? null : "0001")
                .receivedAt(LocalDateTime.of(2026, 11, 1, 10, 0)).build();
    }

    private OrderClaim exchangeClaim(ClaimStatus status, String step, String invoice) {
        return OrderClaim.builder().id(41L).marketplaceAccount(account).platform(Platform.LOTTEON)
                .claimType(ClaimType.EXCHANGE).externalClaimId("X1").externalOrderId("OD2").externalItemId("1")
                .quantity(1).status(status).platformStatus(step)
                .collectInvoiceNo(invoice).collectCarrierCode(invoice == null ? null : "0001")
                .receivedAt(LocalDateTime.of(2026, 11, 1, 10, 0)).build();
    }

    private OrderClaim openClaim(Long id, String odNo, LocalDateTime receivedAt) {
        return OrderClaim.builder().id(id).marketplaceAccount(account).platform(Platform.LOTTEON)
                .claimType(ClaimType.RETURN).externalClaimId("C" + id).externalOrderId(odNo).externalItemId("1")
                .quantity(1).status(ClaimStatus.RECEIVED).platformStatus("03").receivedAt(receivedAt).build();
    }

    private static LotteonClaim row31(OrderClaim claim, String hold) {
        return LotteonClaim.builder().id(1L).orderClaim(claim).procSeq("2").orglProcSeq("1").holdStatus(hold).build();
    }

    private static LotteonClaim row41(OrderClaim claim) {
        return LotteonClaim.builder().id(2L).orderClaim(claim).procSeq("3").orglProcSeq("1").build();
    }

    private static ClaimRecord record(String clmNo, String odNo, ClaimStatus status, String step, String reason) {
        return new ClaimRecord(clmNo, odNo, null, "1", "물티슈 / 대형", 1, status, step, null, reason, null,
                null, null, null, null, null, null, null, null, LocalDateTime.of(2026, 11, 1, 10, 0), null);
    }

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private OrderClaim savedClaim() {
        ArgumentCaptor<OrderClaim> saved = ArgumentCaptor.forClass(OrderClaim.class);
        verify(orderClaimRepository).save(saved.capture());
        return saved.getValue();
    }

    private LotteonClaim savedLotteonClaim() {
        ArgumentCaptor<LotteonClaim> saved = ArgumentCaptor.forClass(LotteonClaim.class);
        verify(lotteonClaimRepository).save(saved.capture());
        return saved.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Map<String, Object>>> linesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static List<ClaimAction> actions(List<ClaimActionOption> options) {
        return options.stream().map(ClaimActionOption::action).toList();
    }

    // ---------------------------------------------------------------- list load

    @Test
    void toRecord_mapsAReturnRowAndSkipsAnAsRow() throws Exception {
        JsonNode returnRow = json("""
                {"odNo":"OD1","clmNo":"C1","odSeq":"1","procSeq":"2","orglProcSeq":"1","odTypCd":"40",
                 "odPrgsStepCd":"03","rtngQty":1,"spdNm":"물티슈","sitmNm":"대형","clmRsnCd":"302",
                 "clmReqDttm":"20261101100000"}""");
        JsonNode asRow = json("""
                {"odNo":"OD1","clmNo":"C2","odSeq":"1","odTypCd":"50","odPrgsStepCd":"03"}""");

        ClaimRecord record = LotteonClaimSyncAdapter.toRecord(returnRow, LocalDateTime.now());
        ClaimRecord skipped = LotteonClaimSyncAdapter.toRecord(asRow, LocalDateTime.now());

        assertThat(record.externalClaimId()).isEqualTo("C1");
        assertThat(record.externalOrderId()).isEqualTo("OD1");
        assertThat(record.externalBoxId()).isNull();
        assertThat(record.externalItemId()).isEqualTo("1");
        assertThat(record.itemName()).isEqualTo("물티슈 / 대형");
        assertThat(record.quantity()).isEqualTo(1);
        assertThat(record.status()).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(record.platformStatus()).isEqualTo("03");
        assertThat(record.reasonCode()).isEqualTo("302");
        assertThat(record.receivedAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 10, 0));
        assertThat(record.requesterName()).isNull();
        assertThat(skipped).isNull();
    }

    @Test
    void syncWithProgress_readsTheFourListsOnceAndCallsNo140() {
        ClaimSyncAdapter.ClaimSyncResult result = syncAdapter(lotteonClaimUpserter).syncWithProgress(account, List.of());

        verify(lotteonOrderApi, times(1)).returnList(eq(account), any(), any());
        verify(lotteonOrderApi, times(1)).returnWithdrawList(eq(account), any(), any());
        verify(lotteonOrderApi, times(1)).exchangeList(eq(account), any(), any());
        verify(lotteonOrderApi, times(1)).exchangeWithdrawList(eq(account), any(), any());
        verify(lotteonOrderApi, never()).progressStates(any(), any(), any());
        assertThat(result.pages()).isEqualTo(4);
    }

    @Test
    void syncExchanges_readsProgressItselfForTheClaimsOnlyRound() {
        syncAdapter(lotteonClaimUpserter).syncExchanges(account);

        verify(lotteonOrderApi, times(14)).progressStates(eq(account), any(), any());
    }

    @Test
    void upsert_createsTheClaimAndItsLotteonRowWithTheReturnHold() {
        OrderLine line = OrderLine.builder().id(11L).build();
        given(lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD1", "1"))
                .willReturn(Optional.of(LotteonOrderLine.builder().orderLine(line).odNo("OD1").odSeq("1").build()));
        given(orderClaimRepository.save(any(OrderClaim.class))).willAnswer(returnsFirstArg());

        upserter().upsert(account, ClaimType.RETURN, record("C1", "OD1", ClaimStatus.RECEIVED, "03", "302"),
                "2", "1", "03");

        OrderClaim claim = savedClaim();
        assertThat(claim.getPlatform()).isEqualTo(Platform.LOTTEON);
        assertThat(claim.getOrderLine()).isSameAs(line);
        LotteonClaim lotteon = savedLotteonClaim();
        assertThat(lotteon.getOrderClaim()).isSameAs(claim);
        assertThat(lotteon.getProcSeq()).isEqualTo("2");
        assertThat(lotteon.getOrglProcSeq()).isEqualTo("1");
        assertThat(lotteon.getHoldStatus()).isEqualTo("03");
    }

    @Test
    void upsert_exchangeNeverTouchesTheHoldStatus() {
        OrderClaim exchange = exchangeClaim(ClaimStatus.RECEIVED, "03", null);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.EXCHANGE, "X1", "1")).willReturn(Optional.of(exchange));
        given(lotteonClaimRepository.findByOrderClaim_Id(41L))
                .willReturn(Optional.of(row41(exchange).toBuilder().holdStatus("03").build()));

        upserter().upsert(account, ClaimType.EXCHANGE, record("X1", "OD2", ClaimStatus.RECEIVED, "03", null),
                "3", "1", null);

        assertThat(savedLotteonClaim().getHoldStatus()).isEqualTo("03");
    }

    @Test
    void upsert_returnWithoutHoldClearsIt() {
        OrderClaim claim = returnClaim(ClaimStatus.RECEIVED, "03", null);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.RETURN, "C1", "1")).willReturn(Optional.of(claim));
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(claim, "09")));

        upserter().upsert(account, ClaimType.RETURN, record("C1", "OD1", ClaimStatus.RECEIVED, "03", null),
                "2", "1", null);

        assertThat(savedLotteonClaim().getHoldStatus()).isNull();
    }

    @Test
    void upsert_listStepDoesNotRewindAProgressedClaim() {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "24", null);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.RETURN, "C1", "1")).willReturn(Optional.of(claim));
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(claim, null)));

        upserter().upsert(account, ClaimType.RETURN, record("C1", "OD1", ClaimStatus.RECEIVED, "03", "305"),
                "2", "1", null);

        OrderClaim saved = savedClaim();
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.IN_PROGRESS);
        assertThat(saved.getPlatformStatus()).isEqualTo("24");
        assertThat(saved.getReasonCode()).isEqualTo("305");
    }

    @Test
    void upsert_completedListStepOverwrites() {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "24", null);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.RETURN, "C1", "1")).willReturn(Optional.of(claim));
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(claim, null)));

        upserter().upsert(account, ClaimType.RETURN, record("C1", "OD1", ClaimStatus.DONE, "27", null),
                "2", "1", null);

        OrderClaim saved = savedClaim();
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.DONE);
        assertThat(saved.getPlatformStatus()).isEqualTo("27");
    }

    // ---------------------------------------------------------------- [140] rows

    @Test
    void applyProgress_retrievalRowMovesTheStatusAndFillsTheCollectInvoice() throws Exception {
        OrderClaim claim = returnClaim(ClaimStatus.RECEIVED, "03", null);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.RETURN, "C1", "1")).willReturn(Optional.of(claim));
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(claim, null)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"RTRV","odTypCd":"40","clmNo":"C1","odNo":"OD1","odSeq":"1","odPrgsStepCd":"24",
                 "invcNo":"R111","dvCoCd":"0001","procSeq":"4"}"""));

        OrderClaim saved = savedClaim();
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.IN_PROGRESS);
        assertThat(saved.getPlatformStatus()).isEqualTo("24");
        assertThat(saved.getCollectInvoiceNo()).isEqualTo("R111");
        assertThat(saved.getCollectCarrierCode()).isEqualTo("0001");
        assertThat(saved.getCollectInvoiceSource()).isEqualTo(CollectInvoiceSource.PLATFORM);
        assertThat(savedLotteonClaim().getProcSeq()).isEqualTo("4");
    }

    @Test
    void applyProgress_blankInvoiceKeepsTheStoredOne() throws Exception {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "24", "R111");
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.RETURN, "C1", "1")).willReturn(Optional.of(claim));
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(claim, null)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"RTRV","odTypCd":"40","clmNo":"C1","odNo":"OD1","odSeq":"1","odPrgsStepCd":"25",
                 "invcNo":"","procSeq":"4"}"""));

        OrderClaim saved = savedClaim();
        assertThat(saved.getCollectInvoiceNo()).isEqualTo("R111");
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.IN_PROGRESS);
        assertThat(saved.getPlatformStatus()).isEqualTo("25");
    }

    @Test
    void applyProgress_doesNotReopenAWithdrawnClaim() throws Exception {
        OrderClaim claim = returnClaim(ClaimStatus.WITHDRAWN, "03", null);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.RETURN, "C1", "1")).willReturn(Optional.of(claim));
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(claim, null)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"RTRV","odTypCd":"40","clmNo":"C1","odNo":"OD1","odSeq":"1","odPrgsStepCd":"24"}"""));

        assertThat(savedClaim().getStatus()).isEqualTo(ClaimStatus.WITHDRAWN);
    }

    @Test
    void applyProgress_reshipRowIsAttachedToTheExchange() throws Exception {
        OrderClaim exchange = exchangeClaim(ClaimStatus.IN_PROGRESS, "25", "X111");
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.EXCHANGE, "X1", "1")).willReturn(Optional.of(exchange));
        given(lotteonClaimRepository.findByOrderClaim_Id(41L)).willReturn(Optional.of(row41(exchange)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"DV","odTypCd":"30","clmNo":"X1","odNo":"OD2","odSeq":"1","procSeq":"5",
                 "odPrgsStepCd":"11","invcNo":"S222","dvCoCd":"0002"}"""));

        LotteonClaim lotteon = savedLotteonClaim();
        assertThat(lotteon.getReshipOdSeq()).isEqualTo("1");
        assertThat(lotteon.getReshipProcSeq()).isEqualTo("5");
        OrderClaim saved = savedClaim();
        assertThat(saved.getReshipInvoiceNo()).isEqualTo("S222");
        assertThat(saved.getReshipCarrierCode()).isEqualTo("0002");
    }

    @Test
    void applyProgress_exchangeRetrievalConfirmedStaysInProgress() throws Exception {
        OrderClaim exchange = exchangeClaim(ClaimStatus.IN_PROGRESS, "25", "X111");
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.EXCHANGE, "X1", "1")).willReturn(Optional.of(exchange));
        given(lotteonClaimRepository.findByOrderClaim_Id(41L)).willReturn(Optional.of(row41(exchange)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"RTRV","odTypCd":"30","clmNo":"X1","odNo":"OD2","odSeq":"1","odPrgsStepCd":"26"}"""));

        OrderClaim saved = savedClaim();
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.IN_PROGRESS);
        assertThat(saved.getPlatformStatus()).isEqualTo("26");
    }

    @Test
    void applyProgress_returnRetrievalConfirmedIsDone() throws Exception {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "25", "R111");
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.RETURN, "C1", "1")).willReturn(Optional.of(claim));
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(claim, null)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"RTRV","odTypCd":"40","clmNo":"C1","odNo":"OD1","odSeq":"1","odPrgsStepCd":"26"}"""));

        OrderClaim saved = savedClaim();
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.DONE);
        assertThat(saved.getPlatformStatus()).isEqualTo("26");
    }

    @Test
    void applyProgress_reshipDeliveredCompletesAConfirmedExchange() throws Exception {
        OrderClaim exchange = exchangeClaim(ClaimStatus.IN_PROGRESS, "26", "X111");
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.EXCHANGE, "X1", "1")).willReturn(Optional.of(exchange));
        given(lotteonClaimRepository.findByOrderClaim_Id(41L)).willReturn(Optional.of(row41(exchange)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"DV","odTypCd":"30","clmNo":"X1","odNo":"OD2","odSeq":"1","procSeq":"5",
                 "odPrgsStepCd":"14"}"""));

        OrderClaim saved = savedClaim();
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.DONE);
        assertThat(saved.getPlatformStatus()).isEqualTo("26");
        assertThat(saved.getReshipInvoiceNo()).isNull();
        LotteonClaim lotteon = savedLotteonClaim();
        assertThat(lotteon.getReshipOdSeq()).isEqualTo("1");
        assertThat(lotteon.getReshipProcSeq()).isEqualTo("5");
    }

    @Test
    void applyProgress_reshipDeliveredBeforeConfirmKeepsTheExchangeOpen() throws Exception {
        OrderClaim beforeConfirm = exchangeClaim(ClaimStatus.IN_PROGRESS, "25", null);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.EXCHANGE, "X1", "1")).willReturn(Optional.of(beforeConfirm));
        given(lotteonClaimRepository.findByOrderClaim_Id(41L)).willReturn(Optional.of(row41(beforeConfirm)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"DV","odTypCd":"30","clmNo":"X1","odNo":"OD2","odSeq":"1","procSeq":"5",
                 "odPrgsStepCd":"15"}"""));

        verify(orderClaimRepository, never()).save(any());
        verify(lotteonClaimRepository, times(1)).save(any());
        clearInvocations(orderClaimRepository, lotteonClaimRepository);

        OrderClaim stale = exchangeClaim(ClaimStatus.STALE, "26", null);
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.EXCHANGE, "X1", "1")).willReturn(Optional.of(stale));
        given(lotteonClaimRepository.findByOrderClaim_Id(41L)).willReturn(Optional.of(row41(stale)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"DV","odTypCd":"30","clmNo":"X1","odNo":"OD2","odSeq":"1","procSeq":"5",
                 "odPrgsStepCd":"14"}"""));

        verify(orderClaimRepository, never()).save(any());
        verify(lotteonClaimRepository, times(1)).save(any());
    }

    @Test
    void applyProgress_retrievalRowKeepsADoneExchangeDone() throws Exception {
        OrderClaim exchange = exchangeClaim(ClaimStatus.DONE, "26", "X111");
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.EXCHANGE, "X1", "1")).willReturn(Optional.of(exchange));
        given(lotteonClaimRepository.findByOrderClaim_Id(41L)).willReturn(Optional.of(row41(exchange)));

        upserter().applyProgress(account, json("""
                {"dvRtrvDvsCd":"RTRV","odTypCd":"30","clmNo":"X1","odNo":"OD2","odSeq":"1","odPrgsStepCd":"26",
                 "invcNo":"X222","dvCoCd":"0001","procSeq":"6"}"""));

        OrderClaim saved = savedClaim();
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.DONE);
        assertThat(saved.getPlatformStatus()).isEqualTo("26");
        assertThat(saved.getCollectInvoiceNo()).isEqualTo("X222");
        assertThat(savedLotteonClaim().getProcSeq()).isEqualTo("6");
    }

    // ---------------------------------------------------------------- D86 · order round

    @Test
    void syncWithProgress_followsOldOpenClaimsByOrderNumberOnce() throws Exception {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        OrderClaim claim31 = returnClaim(ClaimStatus.RECEIVED, "03", null).toBuilder()
                .receivedAt(now.minusDays(20)).build();
        given(orderClaimRepository.findByMarketplaceAccount_IdAndClaimTypeAndExternalClaimIdAndExternalItemId(
                9L, ClaimType.RETURN, "C1", "1")).willReturn(Optional.of(claim31));
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(claim31, null)));
        given(orderClaimRepository.findOpen(9L, ClaimType.RETURN, ClaimStatus.closedStatuses())).willReturn(List.of(
                claim31, openClaim(32L, "OD1", now.minusDays(18)), openClaim(33L, "OD3", now.minusDays(3))));
        given(orderClaimRepository.findOpen(9L, ClaimType.EXCHANGE, ClaimStatus.closedStatuses()))
                .willReturn(List.of());
        given(claimStaleSweeper.sweep(eq(account), anyList())).willAnswer(invocation -> invocation.getArgument(1));
        given(lotteonOrderApi.progressStatesOfOrder(account, "OD1")).willReturn(List.of(json("""
                {"dvRtrvDvsCd":"RTRV","odTypCd":"40","clmNo":"C1","odNo":"OD1","odSeq":"1","odPrgsStepCd":"25"}""")));

        syncAdapter(upserter()).syncWithProgress(account, List.of());

        verify(lotteonOrderApi, times(1)).progressStatesOfOrder(account, "OD1");
        verify(lotteonOrderApi, never()).progressStatesOfOrder(account, "OD3");
        assertThat(savedClaim().getPlatformStatus()).isEqualTo("25");
    }

    @Test
    void syncWithProgress_orderLookupFailureStopsAndPropagates() {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        given(orderClaimRepository.findOpen(9L, ClaimType.RETURN, ClaimStatus.closedStatuses())).willReturn(List.of(
                openClaim(31L, "OD1", now.minusDays(20)), openClaim(34L, "OD4", now.minusDays(20))));
        given(orderClaimRepository.findOpen(9L, ClaimType.EXCHANGE, ClaimStatus.closedStatuses()))
                .willReturn(List.of());
        given(claimStaleSweeper.sweep(eq(account), anyList())).willAnswer(invocation -> invocation.getArgument(1));
        given(lotteonOrderApi.progressStatesOfOrder(account, "OD1"))
                .willThrow(new BusinessException("조회 실패", HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> syncAdapter(lotteonClaimUpserter).syncWithProgress(account, List.of()))
                .isInstanceOf(BusinessException.class).hasMessage("조회 실패");
        verify(lotteonOrderApi, never()).progressStatesOfOrder(account, "OD4");
    }

    @Test
    void syncWithProgress_followsAConfirmedExchangePastThirtyDaysWithoutTheSweeper() {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        OrderClaim claim41 = exchangeClaim(ClaimStatus.IN_PROGRESS, "26", "X111").toBuilder()
                .receivedAt(now.minusDays(45)).build();
        OrderClaim claim42 = exchangeClaim(ClaimStatus.IN_PROGRESS, "25", "X111").toBuilder()
                .id(42L).externalClaimId("X2").externalOrderId("OD5").receivedAt(now.minusDays(45)).build();
        given(orderClaimRepository.findOpen(9L, ClaimType.RETURN, ClaimStatus.closedStatuses())).willReturn(List.of());
        given(orderClaimRepository.findOpen(9L, ClaimType.EXCHANGE, ClaimStatus.closedStatuses()))
                .willReturn(List.of(claim41, claim42));
        given(claimStaleSweeper.sweep(eq(account), anyList())).willAnswer(invocation -> invocation.getArgument(1));

        ClaimSyncAdapter.ClaimSyncResult result = syncAdapter(lotteonClaimUpserter).syncWithProgress(account, List.of());

        verify(claimStaleSweeper, times(1)).sweep(account, List.of(claim42));
        verify(claimStaleSweeper, times(1)).sweep(account, List.of());
        verify(orderClaimRepository, never()).save(any());
        verify(lotteonOrderApi, times(1)).progressStatesOfOrder(account, "OD2");
        verify(lotteonOrderApi, times(1)).progressStatesOfOrder(account, "OD5");
        assertThat(result.staleClosed()).isEqualTo(0);
    }

    @Test
    void syncWithProgress_closesAConfirmedExchangeAfterSixtyDays() {
        OrderClaim claim43 = exchangeClaim(ClaimStatus.IN_PROGRESS, "27", "X111").toBuilder()
                .id(43L).externalClaimId("X3").externalOrderId("OD6")
                .receivedAt(LocalDate.now(SyncWindow.KST).minusDays(60).atStartOfDay().minusSeconds(1)).build();
        given(orderClaimRepository.findOpen(9L, ClaimType.RETURN, ClaimStatus.closedStatuses())).willReturn(List.of());
        given(orderClaimRepository.findOpen(9L, ClaimType.EXCHANGE, ClaimStatus.closedStatuses()))
                .willReturn(List.of(claim43));
        given(claimStaleSweeper.sweep(eq(account), anyList())).willAnswer(invocation -> invocation.getArgument(1));

        ClaimSyncAdapter.ClaimSyncResult result = syncAdapter(lotteonClaimUpserter).syncWithProgress(account, List.of());

        OrderClaim saved = savedClaim();
        assertThat(saved.getId()).isEqualTo(43L);
        assertThat(saved.getStatus()).isEqualTo(ClaimStatus.STALE);
        verify(claimStaleSweeper, times(2)).sweep(account, List.of());
        verify(lotteonOrderApi, never()).progressStatesOfOrder(account, "OD6");
        assertThat(result.staleClosed()).isEqualTo(1);
    }

    @Test
    void orderRound_claimFailureIsPartialOnAManualRound() {
        given(lotteonOrderUpserter.upsertRows(eq(account), anyList())).willReturn(UpsertCount.empty());
        given(lotteonClaimSyncAdapter.syncWithProgress(eq(account), anyList()))
                .willThrow(new BusinessException("조회 실패", HttpStatus.BAD_GATEWAY));
        LotteonOrderSyncServiceImpl service = new LotteonOrderSyncServiceImpl(lotteonOrderApi, lotteonOrderUpserter,
                lotteonCancelRequestUpserter, syncStatusRecorder, lotteonClaimSyncAdapter, List.of());

        assertThatThrownBy(() -> service.sync(account, OrderSyncPreset.QUICK, false))
                .isInstanceOf(BusinessException.class).hasMessage("조회 실패");
        assertThatThrownBy(() -> service.sync(account, OrderSyncPreset.QUICK, true))
                .isInstanceOf(BusinessException.class).hasMessage("조회 실패");

        verify(syncStatusRecorder, times(1))
                .recordPartial(eq(9L), startsWith("반품 · 교환 반영 실패 — "), eq(true), eq(true));
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    // ---------------------------------------------------------------- buttons

    @Test
    void availableActions_collectInvoiceDecidesTheRetrievalButtons() {
        OrderClaim base = returnClaim(ClaimStatus.IN_PROGRESS, "23", null);
        given(lotteonClaimRepository.findByOrderClaim_Id(31L)).willReturn(Optional.of(row31(base, null)));
        LotteonClaimActionAdapter adapter = actionAdapter(lotteonOrderApi);

        assertThat(actions(adapter.availableActions(base, Set.of())))
                .containsExactly(ClaimAction.RETURN_COLLECT_INVOICE, ClaimAction.RETURN_HOLD);
        assertThat(actions(adapter.availableActions(returnClaim(ClaimStatus.IN_PROGRESS, "23", "R111"), Set.of())))
                .containsExactly(ClaimAction.RETURN_RECEIVE_CONFIRM, ClaimAction.RETURN_HOLD);
        assertThat(actions(adapter.availableActions(returnClaim(ClaimStatus.IN_PROGRESS, "25", "R111"), Set.of())))
                .containsExactly(ClaimAction.RETURN_HOLD, ClaimAction.RETURN_APPROVE);
        assertThat(actions(adapter.availableActions(returnClaim(ClaimStatus.IN_PROGRESS, "25", null), Set.of())))
                .containsExactly(ClaimAction.RETURN_HOLD);
    }

    @Test
    void availableActions_holdAndReleaseAlternateDespiteEarlierSuccess() {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "24", "R111");
        Set<ClaimAction> succeeded = Set.of(ClaimAction.RETURN_HOLD, ClaimAction.RETURN_HOLD_RELEASE,
                ClaimAction.RETURN_RECEIVE_CONFIRM);
        given(lotteonClaimRepository.findByOrderClaim_Id(31L))
                .willReturn(Optional.of(row31(claim, "03")), Optional.of(row31(claim, "09")));
        LotteonClaimActionAdapter adapter = actionAdapter(lotteonOrderApi);

        List<ClaimActionOption> onHold = adapter.availableActions(claim, succeeded);
        List<ClaimActionOption> released = adapter.availableActions(claim, succeeded);

        assertThat(actions(onHold)).containsExactly(ClaimAction.RETURN_HOLD_RELEASE);
        assertThat(onHold.get(0).requires()).isEqualTo(ClaimAction.Requires.REASON_TEXT);
        assertThat(onHold.get(0).choices()).isEmpty();
        assertThat(actions(released)).containsExactly(ClaimAction.RETURN_HOLD);
        assertThat(released.get(0).choices()).hasSize(5);
    }

    @Test
    void availableActions_exchangeOffersCollectConfirmAndReship() {
        OrderClaim exchange = exchangeClaim(ClaimStatus.IN_PROGRESS, "25", "X111");
        given(lotteonClaimRepository.findByOrderClaim_Id(41L))
                .willReturn(Optional.of(row41(exchange).toBuilder().reshipOdSeq("1").build()));

        List<ClaimActionOption> options = actionAdapter(lotteonOrderApi).availableActions(exchange, Set.of());

        assertThat(actions(options)).containsExactly(ClaimAction.EXCHANGE_HOLD, ClaimAction.EXCHANGE_COLLECT_CONFIRM,
                ClaimAction.EXCHANGE_RESHIP_INVOICE);
        assertThat(options.get(1).irreversible()).isTrue();
    }

    @Test
    void availableActions_reshipShowsAfterRetrievalConfirmUntilAReshipInvoiceArrives() {
        OrderClaim exchange = exchangeClaim(ClaimStatus.IN_PROGRESS, "26", "X111");
        given(lotteonClaimRepository.findByOrderClaim_Id(41L))
                .willReturn(Optional.of(row41(exchange).toBuilder().reshipOdSeq("1").build()));
        LotteonClaimActionAdapter adapter = actionAdapter(lotteonOrderApi);

        assertThat(actions(adapter.availableActions(exchange, Set.of())))
                .containsExactly(ClaimAction.EXCHANGE_RESHIP_INVOICE);
        assertThat(actions(adapter.availableActions(exchange.toBuilder().reshipInvoiceNo("S222").build(), Set.of())))
                .isEmpty();
        assertThat(actions(adapter.availableActions(exchange, Set.of(ClaimAction.EXCHANGE_RESHIP_INVOICE))))
                .isEmpty();
    }

    @Test
    void availableActions_closedClaimHasNone() {
        assertThat(actionAdapter(lotteonOrderApi)
                .availableActions(returnClaim(ClaimStatus.DONE, "27", "R111"), Set.of())).isEmpty();
    }

    // ---------------------------------------------------------------- sending

    @Test
    void execute_collectInvoiceSends210OnceThenStep24() {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "23", null);
        given(lotteonClaimRepository.findByOrderClaim_IdIn(List.of(31L))).willReturn(List.of(row31(claim, null)));
        given(carrierCodeService.validateDeliveryCompanyCode("0001", Platform.LOTTEON)).willReturn("0001");
        given(lotteonOrderApi.informProgress(eq(account), anyList())).willReturn(List.of());

        ClaimActionOutcome outcome = actionAdapter(lotteonOrderApi).execute(account, List.of(claim),
                new ClaimActionCommand(ClaimAction.RETURN_COLLECT_INVOICE, "0001", "R111", null, null, null));

        ArgumentCaptor<List<Map<String, Object>>> receipt = linesCaptor();
        verify(lotteonOrderApi, times(1)).informIfComplete(eq(account), receipt.capture());
        assertThat(receipt.getValue()).containsExactly(Map.of("dvRtrvDvsCd", "RTRV", "odNo", "OD1", "odSeq", "1",
                "procSeq", "2", "orglProcSeq", "1", "clmNo", "C1", "ifCplYN", "Y"));
        verify(lotteonClaimUpserter, times(1)).markRetrievalInformed(eq(List.of(31L)), any());
        ArgumentCaptor<List<Map<String, Object>>> progress = linesCaptor();
        verify(lotteonOrderApi).informProgress(eq(account), progress.capture());
        Map<String, Object> line = progress.getValue().get(0);
        assertThat(line).containsEntry("odPrgsStepCd", "24").containsEntry("dvCoCd", "0001")
                .containsEntry("invcNoList", List.of("R111")).containsEntry("invcNbr", "1")
                .containsEntry("slQty", "1");
        assertThat(outcome).isEqualTo(new ClaimActionOutcome(true, "0000", null));
    }

    @Test
    void execute_receiveConfirmSkips210WhenInformedAndReusesTheCollectInvoice() {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "24", "R111");
        given(lotteonClaimRepository.findByOrderClaim_IdIn(List.of(31L))).willReturn(List.of(
                row31(claim, null).toBuilder().retrievalInformedAt(LocalDateTime.of(2026, 11, 2, 9, 0)).build()));
        given(lotteonOrderApi.informProgress(eq(account), anyList())).willReturn(List.of());

        actionAdapter(lotteonOrderApi).execute(account, List.of(claim),
                new ClaimActionCommand(ClaimAction.RETURN_RECEIVE_CONFIRM, null, null, null, null, null));

        verify(lotteonOrderApi, never()).informIfComplete(any(), anyList());
        ArgumentCaptor<List<Map<String, Object>>> progress = linesCaptor();
        verify(lotteonOrderApi).informProgress(eq(account), progress.capture());
        assertThat(progress.getValue().get(0)).containsEntry("odPrgsStepCd", "25").containsEntry("dvCoCd", "0001")
                .containsEntry("invcNoList", List.of("R111"));
    }

    @Test
    void execute_lotteonRefusalPropagates() {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "23", null);
        given(lotteonClaimRepository.findByOrderClaim_IdIn(List.of(31L))).willReturn(List.of(row31(claim, null)));
        given(carrierCodeService.validateDeliveryCompanyCode("0001", Platform.LOTTEON)).willReturn("0001");
        given(lotteonOrderApi.informProgress(eq(account), anyList()))
                .willReturn(List.of(new LineFailure("OD1", "1", "3023", "처리가능수량보다 주문수량이 많습니다.")));

        assertThatThrownBy(() -> actionAdapter(lotteonOrderApi).execute(account, List.of(claim),
                new ClaimActionCommand(ClaimAction.RETURN_COLLECT_INVOICE, "0001", "R111", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("처리가능수량보다 주문수량이 많습니다.")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_holdReleaseSends09WithoutReasonCode() throws Exception {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "24", "R111");
        given(lotteonClaimRepository.findByOrderClaim_IdIn(List.of(31L))).willReturn(List.of(row31(claim, "03")));
        given(lotteonApiClient.post(eq(LotteonPaths.RETRIEVAL_EXCEPTION), any(), eq("KEY")))
                .willReturn(json("{\"returnCode\":\"0000\",\"data\":{\"rsltCd\":\"0000\"}}"));

        actionAdapter(new LotteonOrderApi(lotteonApiClient)).execute(account, List.of(claim),
                new ClaimActionCommand(ClaimAction.RETURN_HOLD_RELEASE, null, null, null, "01", "반품비 입금 확인"));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(lotteonApiClient).post(eq(LotteonPaths.RETRIEVAL_EXCEPTION), body.capture(), eq("KEY"));
        Map<String, Object> sent = (Map<String, Object>) body.getValue();
        assertThat(sent).containsOnlyKeys("retrievalExceptionList");
        Map<String, Object> line = ((List<Map<String, Object>>) sent.get("retrievalExceptionList")).get(0);
        assertThat(line).containsEntry("excpProcDvsCd", "09").doesNotContainKey("holdRsnDvsCd")
                .containsEntry("excpProcRsnCnts", "반품비 입금 확인");
        verify(lotteonClaimUpserter, times(1)).markHold(List.of(31L), "09");
    }

    @Test
    void execute_holdRefusesAnUnlistedReason() {
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "24", null);
        given(lotteonClaimRepository.findByOrderClaim_IdIn(List.of(31L))).willReturn(List.of(row31(claim, null)));

        assertThatThrownBy(() -> actionAdapter(lotteonOrderApi).execute(account, List.of(claim),
                new ClaimActionCommand(ClaimAction.RETURN_HOLD, null, null, null, "99", "사유")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("지원하지 않는 사유입니다: 99");
        verifyNoInteractions(lotteonOrderApi);
    }

    @Test
    void execute_reshipSends210ThenStep13OnTheReshipLine() {
        OrderClaim exchange = exchangeClaim(ClaimStatus.IN_PROGRESS, "26", "X111");
        given(lotteonClaimRepository.findByOrderClaim_IdIn(List.of(41L))).willReturn(List.of(
                row41(exchange).toBuilder().reshipOdSeq("1").reshipProcSeq("5").build()));
        given(carrierCodeService.validateDeliveryCompanyCode("0002", Platform.LOTTEON)).willReturn("0002");
        given(lotteonOrderApi.informProgress(eq(account), anyList())).willReturn(List.of());

        actionAdapter(lotteonOrderApi).execute(account, List.of(exchange),
                new ClaimActionCommand(ClaimAction.EXCHANGE_RESHIP_INVOICE, "0002", "S222", null, null, null));

        ArgumentCaptor<List<Map<String, Object>>> receipt = linesCaptor();
        verify(lotteonOrderApi).informIfComplete(eq(account), receipt.capture());
        assertThat(receipt.getValue()).containsExactly(Map.of("dvRtrvDvsCd", "DV", "odNo", "OD2", "odSeq", "1",
                "procSeq", "5", "orglProcSeq", "1", "clmNo", "X1", "ifCplYN", "Y"));
        verify(lotteonClaimUpserter).markReshipInformed(eq(List.of(41L)), any());
        ArgumentCaptor<List<Map<String, Object>>> progress = linesCaptor();
        verify(lotteonOrderApi).informProgress(eq(account), progress.capture());
        assertThat(progress.getValue().get(0)).containsEntry("dvRtrvDvsCd", "DV").containsEntry("odPrgsStepCd", "13")
                .containsEntry("dvCoCd", "0002").containsEntry("invcNoList", List.of("S222"))
                .containsEntry("slQty", "1");
    }

    // ---------------------------------------------------------------- status note · common service

    @Test
    void statusNotes_saysOnHoldOnlyForHold03() {
        OrderClaim claim31 = returnClaim(ClaimStatus.IN_PROGRESS, "24", null);
        OrderClaim claim41 = exchangeClaim(ClaimStatus.IN_PROGRESS, "24", null);
        given(lotteonClaimRepository.findByOrderClaim_IdIn(List.of(31L, 41L))).willReturn(List.of(
                row31(claim31, "03"), row41(claim41).toBuilder().holdStatus("09").build()));

        assertThat(actionAdapter(lotteonOrderApi).statusNotes(List.of(claim31, claim41)))
                .isEqualTo(Map.of(31L, "보류 중"));
    }

    @Test
    void claimActionService_repeatableActionSkipsTheDuplicateGuard() {
        Authentication admin = mock(Authentication.class);
        lenient().doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).when(admin).getAuthorities();
        lenient().when(admin.getName()).thenReturn("admin@test.com");
        SecurityContextHolder.getContext().setAuthentication(admin);
        OrderClaim claim = returnClaim(ClaimStatus.IN_PROGRESS, "24", null);
        given(claimActionAdapter.platform()).willReturn(Platform.LOTTEON);
        given(claimActionAdapter.repeatableActions()).willReturn(Set.of(ClaimAction.RETURN_HOLD));
        given(claimActionAdapter.availableActions(any(), any())).willReturn(List.of(new ClaimActionOption(
                ClaimAction.RETURN_HOLD, "반품 보류", ClaimAction.Requires.REASON, LotteonSellerChoices.HOLD_REASONS,
                false, null)));
        given(claimActionAdapter.execute(any(), anyList(), any())).willReturn(new ClaimActionOutcome(true, "0000",
                null));
        given(orderClaimRepository.findWithAccountById(31L)).willReturn(Optional.of(claim));
        given(orderClaimRepository.findSiblings(9L, ClaimType.RETURN, "C1")).willReturn(List.of(claim));
        given(orderClaimActionRepository.existsByOrderClaim_IdInAndActionAndSucceededTrue(anyList(), any()))
                .willReturn(true);
        ClaimActionServiceImpl service = new ClaimActionServiceImpl(List.of(claimActionAdapter),
                orderClaimRepository, orderClaimActionRepository, collectInvoiceRecorder);

        ClaimActionResponse response = service.execute(31L,
                new ClaimActionRequest(ClaimAction.RETURN_HOLD, null, null, null, "01", "사유"));

        assertThat(response.succeeded()).isTrue();
        assertThatThrownBy(() -> service.execute(31L,
                new ClaimActionRequest(ClaimAction.RETURN_APPROVE, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 처리된 접수입니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void claimActionService_statusNotesComeFromTheClaimsPlatformAdapter() {
        OrderClaim claim31 = returnClaim(ClaimStatus.IN_PROGRESS, "24", null);
        OrderClaim claim51 = OrderClaim.builder().id(51L).platform(Platform.COUPANG).claimType(ClaimType.RETURN)
                .build();
        given(claimActionAdapter.platform()).willReturn(Platform.LOTTEON);
        given(claimActionAdapter.statusNotes(List.of(claim31))).willReturn(Map.of(31L, "보류 중"));
        ClaimActionServiceImpl service = new ClaimActionServiceImpl(List.of(claimActionAdapter),
                orderClaimRepository, orderClaimActionRepository, collectInvoiceRecorder);

        assertThat(service.statusNotes(List.of(claim31, claim51))).isEqualTo(Map.of(31L, "보류 중"));
    }

    @Test
    void claimActionRequest_reasonTextNeedsOnlyText() {
        new ClaimActionRequest(ClaimAction.RETURN_HOLD_RELEASE, null, null, null, null, "사유").validateFor();

        assertThatThrownBy(() -> new ClaimActionRequest(ClaimAction.RETURN_HOLD_RELEASE, null, null, null, null, " ")
                .validateFor())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사유는 필수입니다");
    }
}
