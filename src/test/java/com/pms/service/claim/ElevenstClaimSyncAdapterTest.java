package com.pms.service.claim;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.OrderClaimRepository;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D15 · D25 · D29: six lists → order_claim rows with the 11st field mapping and status table. */
@ExtendWith(MockitoExtension.class)
class ElevenstClaimSyncAdapterTest {

    @Mock private ElevenstOrderApi orderApi;
    @Mock private ElevenstClaimUpserter claimUpserter;
    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private ClaimStaleSweeper claimStaleSweeper;
    @InjectMocks private ElevenstClaimSyncAdapter adapter;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    @Test
    void syncExchanges_readsReturnAndExchangeLists_andMapsRows() {
        given(orderApi.listByMinute(any(), anyString(), anyString(), any(), any())).willReturn(List.of());
        given(orderApi.listByMinute(eq(account), eq(ElevenstPaths.RETURN_REQUESTS), eq("clmReqSeq"), any(), any()))
                .willReturn(List.of(Map.of("clmReqSeq", "C1", "ordNo", "O1", "ordPrdSeq", "2", "clmStat", "105",
                        "clmReqQty", "1", "clmReqRsn", "101", "optName", "검정", "twPrdInvcNo", "77777",
                        "reqDt", "2026-10-15 09:00:00")));

        adapter.syncExchanges(account);

        ArgumentCaptor<ClaimRecord> record = ArgumentCaptor.forClass(ClaimRecord.class);
        verify(claimUpserter).upsert(eq(account), eq(ClaimType.RETURN), record.capture());
        assertThat(record.getValue().externalClaimId()).isEqualTo("C1");
        assertThat(record.getValue().externalItemId()).isEqualTo("2");
        assertThat(record.getValue().externalBoxId()).isNull();
        assertThat(record.getValue().status()).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(record.getValue().collectInvoiceNo()).isEqualTo("77777");
        assertThat(record.getValue().receivedAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 0));
        verify(orderApi).listByMinute(eq(account), eq(ElevenstPaths.EXCHANGE_WITHDRAWN), eq("clmReqSeq"), any(), any());
    }

    @Test
    void statusTable_D25() {
        assertThat(ElevenstClaimSyncAdapter.status("104")).isEqualTo(ClaimStatus.IN_PROGRESS);
        assertThat(ElevenstClaimSyncAdapter.status("106")).isEqualTo(ClaimStatus.DONE);
        assertThat(ElevenstClaimSyncAdapter.status("107")).isEqualTo(ClaimStatus.REJECTED);
        assertThat(ElevenstClaimSyncAdapter.status("233")).isEqualTo(ClaimStatus.WITHDRAWN);
        assertThat(ElevenstClaimSyncAdapter.status("999")).isEqualTo(ClaimStatus.RECEIVED);
    }

    @Test
    void rowWithoutClmStat_isSkipped() {
        assertThat(ElevenstClaimSyncAdapter.toRecord(Map.of("clmReqSeq", "C1", "ordNo", "O1", "ordPrdSeq", "2"),
                LocalDateTime.now())).isNull();
    }
}
