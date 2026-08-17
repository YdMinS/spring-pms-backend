package com.pms.service;

import com.pms.dto.request.BatchChannelAddRequest;
import com.pms.dto.response.BatchChannelAddResponse;
import com.pms.dto.response.ChannelAddResponse;
import com.pms.exception.DuplicateChannelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Batch channel-add orchestrator (FEATURE_2608_06 / 15): the catch-and-continue loop only. The per-cell
 * {@code REQUIRES_NEW} rollback isolation cannot be proven here (Mockito has no real transaction) — the
 * self proxy's {@link ChannelAddService#addChannel} is stubbed. This verifies that a failing target is
 * caught and the loop keeps going (partial success), which is the MUST-KEEP behaviour.
 */
@ExtendWith(MockitoExtension.class)
class BatchChannelAddServiceTest {

    @Mock private com.pms.repository.MasterProductRepository masterProductRepository;
    @Mock private com.pms.repository.MasterProductOptionRepository masterProductOptionRepository;
    @Mock private com.pms.repository.MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock private com.pms.repository.ProductListingRepository productListingRepository;
    @Mock private com.pms.repository.ProductListingOptionRepository productListingOptionRepository;
    @Mock private com.pms.repository.ProductListingProductRepository productListingProductRepository;
    @Mock private com.pms.repository.SellerRepository sellerRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private ListingAssetService listingAssetService;

    /** Injected into the {@code self} field (the REQUIRES_NEW proxy) so we can stub per-target outcomes. */
    @Mock private ChannelAddService self;

    @InjectMocks private ChannelAddServiceImpl service;

    private static final Long MASTER_ID = 1L;

    @BeforeEach
    void wireSelfProxy() {
        // Mockito's @InjectMocks skips injecting a mock whose type matches the class under test (self-type),
        // so the @Lazy self field is set explicitly here to the proxy stub.
        ReflectionTestUtils.setField(service, "self", self);
    }

    private BatchChannelAddRequest request() {
        return BatchChannelAddRequest.builder().targets(List.of(
                BatchChannelAddRequest.Target.builder().sellerId(1L).platform("COUPANG").build(),
                BatchChannelAddRequest.Target.builder().sellerId(2L).platform("COUPANG").build(),
                BatchChannelAddRequest.Target.builder().sellerId(3L).platform("COUPANG").build())).build();
    }

    @Test
    void addChannelsBatch_allSucceed_returnsAllSucceeded() {
        given(self.addChannel(eq(MASTER_ID), any()))
                .willReturn(ChannelAddResponse.builder().productListingId(10L).build())
                .willReturn(ChannelAddResponse.builder().productListingId(20L).build())
                .willReturn(ChannelAddResponse.builder().productListingId(30L).build());

        BatchChannelAddResponse response = service.addChannelsBatch(MASTER_ID, request());

        assertThat(response.getRequested()).isEqualTo(3);
        assertThat(response.getSucceeded()).isEqualTo(3);
        assertThat(response.getFailed()).isEqualTo(0);
        assertThat(response.getResults()).hasSize(3)
                .allSatisfy(r -> {
                    assertThat(r.isSuccess()).isTrue();
                    assertThat(r.getProductListingId()).isNotNull();
                });
        verify(self, times(3)).addChannel(eq(MASTER_ID), any());
    }

    @Test
    void addChannelsBatch_oneFails_isolatesFailure_andContinues() {
        // 2nd target throws (e.g. already-registered 409); 1st and 3rd succeed.
        given(self.addChannel(eq(MASTER_ID), any()))
                .willReturn(ChannelAddResponse.builder().productListingId(10L).build())
                .willThrow(new DuplicateChannelException())
                .willReturn(ChannelAddResponse.builder().productListingId(30L).build());

        BatchChannelAddResponse response = service.addChannelsBatch(MASTER_ID, request());

        assertThat(response.getRequested()).isEqualTo(3);
        assertThat(response.getSucceeded()).isEqualTo(2);
        assertThat(response.getFailed()).isEqualTo(1);
        // The failed result carries an error message; the loop did NOT stop at the first failure.
        BatchChannelAddResponse.Result failed = response.getResults().get(1);
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getErrorMessage()).isNotBlank();
        assertThat(failed.getProductListingId()).isNull();
        verify(self, times(3)).addChannel(eq(MASTER_ID), any());
    }
}
