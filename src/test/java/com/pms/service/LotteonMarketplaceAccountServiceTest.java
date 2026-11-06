package com.pms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.EsmProperties;
import com.pms.config.LotteonProperties;
import com.pms.domain.LotteonAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.ElevenstAccountCredentialRepository;
import com.pms.repository.ElevenstChannelDefaultRepository;
import com.pms.repository.EsmAccountCredentialRepository;
import com.pms.repository.LotteonAccountCredentialRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import com.pms.service.esm.EsmApiClient;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonCredentials;
import com.pms.service.lotteon.LotteonPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_24 / D4 · D5 · D6 · D57: the LotteON sales channel key. */
@ExtendWith(MockitoExtension.class)
class LotteonMarketplaceAccountServiceTest {

    @Mock private MarketplaceAccountRepository repository;
    @Mock private CoupangAccountCredentialRepository credentialRepository;
    @Mock private ElevenstAccountCredentialRepository elevenstCredentialRepository;
    @Mock private ElevenstChannelDefaultRepository elevenstChannelDefaultRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private ThumbnailTemplateRepository thumbnailTemplateRepository;
    @Mock private DetailTemplateRepository detailTemplateRepository;
    @Mock private EsmAccountCredentialRepository esmCredentialRepository;
    @Mock private EsmApiClient esmApiClient;
    @Mock private EsmProperties esmProperties;
    @Mock private LotteonAccountCredentialRepository lotteonCredentialRepository;
    @Mock private LotteonApiClient lotteonApiClient;
    @Mock private LotteonProperties lotteonProperties;
    @InjectMocks private MarketplaceAccountServiceImpl service;

    private static final Long SELLER_ID = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private Seller seller() {
        return Seller.builder().id(SELLER_ID).sellerName("셀러").build();
    }

    private MarketplaceAccountRequest.MarketplaceAccountRequestBuilder lotteonRequest(String apiKey) {
        return MarketplaceAccountRequest.builder().sellerId(SELLER_ID).platform("LOTTEON").apiKey(apiKey);
    }

    private void givenIdentity() throws Exception {
        given(lotteonProperties.isOutboundEnabled()).willReturn(true);
        given(lotteonApiClient.get(LotteonPaths.IDENTITY, "KEY-1")).willReturn(JSON.readTree(
                "{\"returnCode\":\"0000\",\"data\":{\"trGrpCd\":\"SR\",\"trNo\":\"LO100\",\"trNm\":\"셀러\"}}"));
    }

    private void givenSaves() {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(lotteonCredentialRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_lotteon_readsIdentity_savesKeyNumbersAndTime_neverExposesTheKey() throws Exception {
        givenIdentity();
        givenSaves();

        MarketplaceAccountResponse response = service.create(lotteonRequest("  KEY-1  ").build());

        ArgumentCaptor<LotteonAccountCredential> captor = ArgumentCaptor.forClass(LotteonAccountCredential.class);
        verify(lotteonCredentialRepository).save(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("KEY-1");
        assertThat(captor.getValue().getTrGrpCd()).isEqualTo("SR");
        assertThat(captor.getValue().getTrNo()).isEqualTo("LO100");
        assertThat(captor.getValue().getKeySavedAt()).isNotNull();
        verify(credentialRepository, never()).save(any());
        assertThat(response.getPlatform()).isEqualTo("LOTTEON");
        assertThat(response.getLotteonKeySavedAt()).isNotNull();
        assertThat(response.getLotteonKeyRenewalNeeded()).isFalse();
        assertThat(response.getVendorId()).isNull();
    }

    @Test
    void create_lotteon_withoutKey_rejected() {
        assertThatThrownBy(() -> service.create(lotteonRequest(" ").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("롯데ON 인증키를 입력하세요.");
        verify(repository, never()).save(any());
    }

    @Test
    void create_lotteon_keyRefused_is400WithLotteonText_andNothingSaved() {
        given(lotteonProperties.isOutboundEnabled()).willReturn(true);
        given(lotteonApiClient.get(anyString(), anyString()))
                .willThrow(new BusinessException("등록되지 않은 OpenAPI Key 입니다.", HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> service.create(lotteonRequest("KEY-1").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("등록되지 않은 OpenAPI Key 입니다.");
        verify(repository, never()).save(any());
        verify(lotteonCredentialRepository, never()).save(any());
    }

    @Test
    void create_lotteon_onAServerThatDoesNotCallLotteon_savesTheKeyAlone() {
        given(lotteonProperties.isOutboundEnabled()).willReturn(false);
        givenSaves();

        service.create(lotteonRequest("KEY-1").build());

        verify(lotteonApiClient, never()).get(anyString(), anyString());
        ArgumentCaptor<LotteonAccountCredential> captor = ArgumentCaptor.forClass(LotteonAccountCredential.class);
        verify(lotteonCredentialRepository).save(captor.capture());
        assertThat(captor.getValue().getTrGrpCd()).isNull();
        assertThat(captor.getValue().getTrNo()).isNull();
    }

    @Test
    void update_lotteon_blankOrSameKey_keepsTheRow_andDoesNotCallLotteon() {
        LotteonAccountCredential stored = LotteonAccountCredential.builder().id(9L).apiKey("KEY-1")
                .trGrpCd("SR").trNo("LO100").keySavedAt(LocalDateTime.now().minusDays(10)).build();
        MarketplaceAccount existing = MarketplaceAccountFixture.lotteonStubBuilder("KEY-1", "SR", "LO100")
                .id(60L).seller(seller()).lotteonCredential(stored).build();
        given(repository.findById(60L)).willReturn(Optional.of(existing));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.update(60L, lotteonRequest("").build());
        service.update(60L, lotteonRequest("KEY-1").build());

        verify(lotteonApiClient, never()).get(anyString(), anyString());
        verify(lotteonCredentialRepository, never()).save(any());
    }

    @Test
    void update_lotteon_newKey_isCheckedAndRestartsTheClock() throws Exception {
        LotteonAccountCredential stored = LotteonAccountCredential.builder().id(9L).apiKey("OLD")
                .keySavedAt(LocalDateTime.now().minusDays(400)).build();
        MarketplaceAccount existing = MarketplaceAccountFixture.lotteonStubBuilder("OLD", null, null)
                .id(60L).seller(seller()).lotteonCredential(stored).build();
        given(repository.findById(60L)).willReturn(Optional.of(existing));
        givenIdentity();
        givenSaves();

        MarketplaceAccountResponse response = service.update(60L, lotteonRequest("KEY-1").build());

        ArgumentCaptor<LotteonAccountCredential> captor = ArgumentCaptor.forClass(LotteonAccountCredential.class);
        verify(lotteonCredentialRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9L);
        assertThat(captor.getValue().getApiKey()).isEqualTo("KEY-1");
        assertThat(captor.getValue().getTrNo()).isEqualTo("LO100");
        assertThat(response.getLotteonKeyRenewalNeeded()).isFalse();
    }

    @Test
    void renewalNeeded_from335DaysAfterTheSave() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 1, 0, 0);
        assertThat(LotteonCredentials.renewalNeeded(LotteonAccountCredential.builder()
                .keySavedAt(now.minusDays(334)).build(), now)).isFalse();
        assertThat(LotteonCredentials.renewalNeeded(LotteonAccountCredential.builder()
                .keySavedAt(now.minusDays(335)).build(), now)).isTrue();
    }
}
