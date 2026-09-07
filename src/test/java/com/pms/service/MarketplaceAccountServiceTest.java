package com.pms.service;

import com.pms.domain.CoupangAccountCredential;
import com.pms.domain.DetailTemplate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @Mock private SellerRepository sellerRepository;
    @Mock private ThumbnailTemplateRepository thumbnailTemplateRepository;
    @Mock private DetailTemplateRepository detailTemplateRepository;
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
}
