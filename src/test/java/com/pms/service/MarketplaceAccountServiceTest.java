package com.pms.service;

import com.pms.config.EsmProperties;
import com.pms.domain.CoupangAccountCredential;
import com.pms.domain.DetailTemplate;
import com.pms.domain.ElevenstAccountCredential;
import com.pms.domain.ElevenstChannelDefault;
import com.pms.domain.EsmAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.ElevenstNoticesRequest;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.ElevenstAccountCredentialRepository;
import com.pms.repository.ElevenstChannelDefaultRepository;
import com.pms.repository.EsmAccountCredentialRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import com.pms.service.esm.EsmApiClient;
import com.pms.service.esm.EsmKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Channel template assignment on account CRUD (FEATURE_2608_06 / 21) + the credential split
 * (FEATURE_2609_26 / 02): the account row stays neutral while the Coupang HMAC fields go to
 * {@link CoupangAccountCredential}. The response shape is unchanged for the two clients.
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceAccountServiceTest {

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
    @InjectMocks private MarketplaceAccountServiceImpl service;

    private static final Long SELLER_ID = 1L;

    /** create/update 는 자격증명을 함께 저장한다 — 저장한 인스턴스를 그대로 돌려준다. */
    private void stubCredentialSave() {
        given(credentialRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(inv -> inv.getArgument(0));
    }

    private Seller seller() {
        return Seller.builder().id(SELLER_ID).sellerName("셀러").build();
    }

    private MarketplaceAccountRequest.MarketplaceAccountRequestBuilder baseRequest() {
        return MarketplaceAccountRequest.builder()
                .sellerId(SELLER_ID).platform("COUPANG").vendorId("V1").accessKey("ak").secretKey("sk");
    }

    @Test
    void create_withTemplateIds_validatesAndAssigns() {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(thumbnailTemplateRepository.findById(9L))
                .willReturn(Optional.of(ThumbnailTemplate.builder().id(9L).name("t").build()));
        given(detailTemplateRepository.findById(5L))
                .willReturn(Optional.of(DetailTemplate.builder().id(5L).name("d").active(true).isDefault(true).build()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        stubCredentialSave();

        MarketplaceAccountResponse response = service.create(
                baseRequest().thumbnailTemplateId(9L).detailTemplateId(5L).build());

        ArgumentCaptor<MarketplaceAccount> captor = ArgumentCaptor.forClass(MarketplaceAccount.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getThumbnailTemplate().getId()).isEqualTo(9L);
        assertThat(captor.getValue().getDetailTemplate().getId()).isEqualTo(5L);
        assertThat(response.getThumbnailTemplateId()).isEqualTo(9L);
        assertThat(response.getDetailTemplateId()).isEqualTo(5L);
    }

    @Test
    void create_withMissingTemplateId_throws404() {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(thumbnailTemplateRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(baseRequest().thumbnailTemplateId(999L).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void vendorUserId_savedOnCreate_replacedOnUpdate() {
        // create: the request vendorUserId lands on the credential row, not on the account.
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        stubCredentialSave();
        service.create(baseRequest().vendorUserId("wing_old").build());
        ArgumentCaptor<CoupangAccountCredential> createCaptor =
                ArgumentCaptor.forClass(CoupangAccountCredential.class);
        verify(credentialRepository).save(createCaptor.capture());
        assertThat(createCaptor.getValue().getVendorUserId()).isEqualTo("wing_old");

        // update: request value directly replaces (same semantics as vendorId/accessKey, full overwrite).
        MarketplaceAccount existing = MarketplaceAccountFixture.coupangStubBuilder("V1", "wing_old")
                .id(50L).seller(seller()).build();
        given(repository.findById(50L)).willReturn(Optional.of(existing));
        MarketplaceAccountResponse response = service.update(
                50L, baseRequest().secretKey(null).vendorUserId("wing_new").build());
        assertThat(response.getVendorUserId()).isEqualTo("wing_new");
    }

    @Test
    void create_persistsCredentialRow_andNeverExposesSecretKey() {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        stubCredentialSave();

        MarketplaceAccountResponse response = service.create(baseRequest().build());

        ArgumentCaptor<CoupangAccountCredential> captor =
                ArgumentCaptor.forClass(CoupangAccountCredential.class);
        verify(credentialRepository).save(captor.capture());
        CoupangAccountCredential saved = captor.getValue();
        assertThat(saved.getVendorId()).isEqualTo("V1");
        assertThat(saved.getAccessKey()).isEqualTo("ak");
        assertThat(saved.getSecretKey()).isEqualTo("sk");
        assertThat(saved.getMarketplaceAccount()).isNotNull();
        // ⚠️ tenantId 는 Hibernate 가 INSERT 시 스탬프한다 — 빌더에서 세팅하면 안 된다.
        assertThat(saved.getTenantId()).isNull();

        // 응답 구조는 예전 그대로(평평한 3필드). secretKey 는 응답 DTO 에 필드 자체가 없다.
        assertThat(response.getVendorId()).isEqualTo("V1");
        assertThat(response.getVendorUserId()).isNull();
        assertThat(response.getAccessKey()).isEqualTo("ak");
    }

    @Test
    void update_blankSecretKey_keepsExistingAndUpdatesSameRow() {
        CoupangAccountCredential existingCred = MarketplaceAccountFixture
                .credential("V1", "wing", "old-ak", "old-sk").toBuilder().id(7L).build();
        MarketplaceAccount existing = MarketplaceAccountFixture.coupangStubBuilder("V1", "wing")
                .id(50L).seller(seller()).coupangCredential(existingCred).build();
        given(repository.findById(50L)).willReturn(Optional.of(existing));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        stubCredentialSave();

        service.update(50L, baseRequest().secretKey("  ").accessKey("new-ak").build());

        ArgumentCaptor<CoupangAccountCredential> captor =
                ArgumentCaptor.forClass(CoupangAccountCredential.class);
        verify(credentialRepository).save(captor.capture());
        // 기존 행을 갱신한다(id 유지) — 새 인스턴스면 uq_coupang_cred_account 위반.
        assertThat(captor.getValue().getId()).isEqualTo(7L);
        assertThat(captor.getValue().getSecretKey()).isEqualTo("old-sk");
        assertThat(captor.getValue().getAccessKey()).isEqualTo("new-ak");
    }

    @Test
    void create_naverPlatform_rejected() {
        assertThatThrownBy(() -> service.create(baseRequest().platform("NAVER").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("네이버");
    }

    // FEATURE_2610_13 / D5 · D88: an 11st account stores only its API key, encrypted, never echoed back.
    @Test
    void create_elevenst_savesKeyWithFreshUseTime_andNeverExposesIt() {
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        given(elevenstCredentialRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(inv -> inv.getArgument(0));

        MarketplaceAccountResponse response = service.create(MarketplaceAccountRequest.builder()
                .sellerId(SELLER_ID).platform("ELEVENST").apiKey("  KEY-1  ").build());

        ArgumentCaptor<ElevenstAccountCredential> captor = ArgumentCaptor.forClass(ElevenstAccountCredential.class);
        verify(elevenstCredentialRepository).save(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("KEY-1");
        assertThat(captor.getValue().getKeyLastUsedAt()).isNotNull();
        verify(credentialRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(response.getPlatform()).isEqualTo("ELEVENST");
        assertThat(response.getElevenstKeyRenewalNeeded()).isFalse();
        assertThat(response.getVendorId()).isNull();
    }

    @Test
    void create_elevenst_withoutKey_rejected() {
        assertThatThrownBy(() -> service.create(MarketplaceAccountRequest.builder()
                .sellerId(SELLER_ID).platform("ELEVENST").apiKey(" ").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가 API 키를 입력하세요.");
    }

    @Test
    void update_elevenst_blankKey_keepsExistingRow() {
        ElevenstAccountCredential stored = ElevenstAccountCredential.builder().id(9L).apiKey("OLD").build();
        MarketplaceAccount existing = MarketplaceAccountFixture.elevenstStubBuilder("OLD")
                .id(60L).seller(seller()).elevenstCredential(stored).build();
        given(repository.findById(60L)).willReturn(Optional.of(existing));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));

        service.update(60L, MarketplaceAccountRequest.builder()
                .sellerId(SELLER_ID).platform("ELEVENST").apiKey("").build());

        verify(elevenstCredentialRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void update_platformChange_rejected() {
        MarketplaceAccount existing = MarketplaceAccountFixture.coupangStubBuilder("V1", null)
                .id(50L).seller(seller()).build();
        given(repository.findById(50L)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(50L, MarketplaceAccountRequest.builder()
                .sellerId(SELLER_ID).platform("ELEVENST").apiKey("K").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매채널의 플랫폼은 바꿀 수 없습니다.");
    }

    @Test
    void create_coupang_withoutVendorId_rejected() {
        assertThatThrownBy(() -> service.create(baseRequest().vendorId(" ").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("vendorId is required");
    }

    @Test
    void update_nullTemplateId_keepsExisting_valueReplaces() {
        ThumbnailTemplate oldThumb = ThumbnailTemplate.builder().id(1L).name("old").build();
        MarketplaceAccount existing = MarketplaceAccountFixture.coupangStubBuilder("V1", null)
                .id(50L).seller(seller())
                .thumbnailTemplate(oldThumb).detailTemplate(null).build();
        given(repository.findById(50L)).willReturn(Optional.of(existing));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(detailTemplateRepository.findById(5L))
                .willReturn(Optional.of(DetailTemplate.builder().id(5L).name("d").active(true).isDefault(true).build()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        stubCredentialSave();

        // thumbnailTemplateId null → keep existing (id 1); detailTemplateId 5 → replace null with 5.
        service.update(50L, baseRequest().secretKey(null).detailTemplateId(5L).build());

        ArgumentCaptor<MarketplaceAccount> captor = ArgumentCaptor.forClass(MarketplaceAccount.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getThumbnailTemplate().getId()).isEqualTo(1L);   // kept
        assertThat(captor.getValue().getDetailTemplate().getId()).isEqualTo(5L);      // replaced
    }

    // FEATURE_2610_16 / D7 · D16: the existing row is replaced in place; blank text is stored as null.
    @Test
    void updateElevenstNotices_trimsAndStoresBlankAsNull_onTheExistingRow() {
        MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("K").id(60L).seller(seller()).build();
        given(repository.findById(60L)).willReturn(Optional.of(account));
        given(elevenstChannelDefaultRepository.findByMarketplaceAccountId(60L)).willReturn(Optional.of(
                ElevenstChannelDefault.builder().id(3L).marketplaceAccount(account).asNotice("옛 글").build()));

        service.updateElevenstNotices(60L, ElevenstNoticesRequest.builder()
                .asNotice("  고객 문의에 남겨주시면 안내해 드립니다.  ").returnExchangeNotice("   ").build());

        ArgumentCaptor<ElevenstChannelDefault> captor = ArgumentCaptor.forClass(ElevenstChannelDefault.class);
        verify(elevenstChannelDefaultRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(3L);
        assertThat(captor.getValue().getAsNotice()).isEqualTo("고객 문의에 남겨주시면 안내해 드립니다.");
        assertThat(captor.getValue().getReturnExchangeNotice()).isNull();
    }

    // FEATURE_2610_16 / D7 · D8: a Coupang channel and an over-limit text are both 400 — nothing is saved.
    @Test
    void updateElevenstNotices_coupangChannelOrOverLimit_rejected() {
        given(repository.findById(50L)).willReturn(Optional.of(
                MarketplaceAccountFixture.coupangStubBuilder("V1", null).id(50L).seller(seller()).build()));
        given(repository.findById(60L)).willReturn(Optional.of(
                MarketplaceAccountFixture.elevenstStubBuilder("K").id(60L).seller(seller()).build()));

        assertThatThrownBy(() -> service.updateElevenstNotices(50L,
                ElevenstNoticesRequest.builder().asNotice("A/S").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가 판매채널이 아닙니다");
        assertThatThrownBy(() -> service.updateElevenstNotices(60L,
                ElevenstNoticesRequest.builder().returnExchangeNotice("가".repeat(2001)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가는 약 2,000자까지입니다");
        verify(elevenstChannelDefaultRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    // FEATURE_2610_16 / D7: the response carries the notice defaults of an 11st channel only.
    @Test
    void get_mapsNoticeDefaults_forElevenstOnly() {
        given(repository.findById(60L)).willReturn(Optional.of(
                MarketplaceAccountFixture.elevenstStubBuilder("K").id(60L).seller(seller()).build()));
        given(elevenstChannelDefaultRepository.findByMarketplaceAccountId(60L)).willReturn(Optional.of(
                ElevenstChannelDefault.builder().asNotice("A/S 글").returnExchangeNotice("반품 글").build()));
        given(repository.findById(50L)).willReturn(Optional.of(
                MarketplaceAccountFixture.coupangStubBuilder("V1", null).id(50L).seller(seller()).build()));

        MarketplaceAccountResponse elevenst = service.get(60L);
        MarketplaceAccountResponse coupang = service.get(50L);

        assertThat(elevenst.getAsNotice()).isEqualTo("A/S 글");
        assertThat(elevenst.getReturnExchangeNotice()).isEqualTo("반품 글");
        assertThat(coupang.getAsNotice()).isNull();
        verify(elevenstChannelDefaultRepository, org.mockito.Mockito.never()).findByMarketplaceAccountId(50L);
    }

    // FEATURE_2610_26 / D4 · D5 · D7: an ESM account stores four values; the key is checked with ESM first.
    private MarketplaceAccountRequest.MarketplaceAccountRequestBuilder esmRequest() {
        return MarketplaceAccountRequest.builder().sellerId(SELLER_ID).platform("ESM")
                .esmMasterId(" master ").esmSecretKey(" SECRET ").gmarketSellerId(" g-id ").auctionSellerId(" a-id ");
    }

    @Test
    void create_esm_checksKeyThenSavesFourValues_andNeverExposesTheSecret() {
        given(esmProperties.isOutboundEnabled()).willReturn(true);
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        given(esmCredentialRepository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));

        MarketplaceAccountResponse response = service.create(esmRequest().build());

        verify(esmApiClient).get("/item/v1/shipping/places?pageSize=1&pageIndex=1",
                new EsmKey("master", "SECRET", "g-id", "a-id"));
        ArgumentCaptor<EsmAccountCredential> captor = ArgumentCaptor.forClass(EsmAccountCredential.class);
        verify(esmCredentialRepository).save(captor.capture());
        assertThat(captor.getValue().getMasterId()).isEqualTo("master");
        assertThat(captor.getValue().getSecretKey()).isEqualTo("SECRET");
        assertThat(captor.getValue().getGmarketSellerId()).isEqualTo("g-id");
        assertThat(captor.getValue().getAuctionSellerId()).isEqualTo("a-id");
        verify(credentialRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(response.getPlatform()).isEqualTo("ESM");
        assertThat(response.getEsmMasterId()).isEqualTo("master");
        assertThat(response.getGmarketSellerId()).isEqualTo("g-id");
        assertThat(response.getAuctionSellerId()).isEqualTo("a-id");
        assertThat(response.getVendorId()).isNull();
    }

    @Test
    void create_esm_withOneSellerIdMissing_rejected() {
        assertThatThrownBy(() -> service.create(esmRequest().auctionSellerId(" ").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("G마켓 판매자 ID 와 옥션 판매자 ID 를 모두 입력하세요");
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void create_esm_keyRefusedByEsm_is400WithEsmText_andNothingSaved() {
        given(esmProperties.isOutboundEnabled()).willReturn(true);
        given(esmApiClient.get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .willThrow(new BusinessException("The user does not have right access to the api",
                        HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> service.create(esmRequest().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The user does not have right access to the api");
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        verify(esmCredentialRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void create_esm_onAServerThatDoesNotCallEsm_savesWithoutTheCheck() {
        given(esmProperties.isOutboundEnabled()).willReturn(false);
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        given(esmCredentialRepository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));

        service.create(esmRequest().build());

        verify(esmApiClient, org.mockito.Mockito.never())
                .get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(esmCredentialRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void update_esm_blankSecretAndSameValues_keepsTheSecret_andDoesNotCallEsm() {
        EsmAccountCredential stored = EsmAccountCredential.builder().id(9L)
                .masterId("master").secretKey("SECRET").gmarketSellerId("g-id").auctionSellerId("a-id").build();
        MarketplaceAccount existing = MarketplaceAccountFixture.esmStubBuilder("master", "SECRET", "g-id", "a-id")
                .id(70L).seller(seller()).esmCredential(stored).build();
        given(repository.findById(70L)).willReturn(Optional.of(existing));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        given(esmCredentialRepository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));

        service.update(70L, esmRequest().esmSecretKey("").build());

        verify(esmApiClient, org.mockito.Mockito.never())
                .get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<EsmAccountCredential> captor = ArgumentCaptor.forClass(EsmAccountCredential.class);
        verify(esmCredentialRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9L);
        assertThat(captor.getValue().getSecretKey()).isEqualTo("SECRET");
    }
}
