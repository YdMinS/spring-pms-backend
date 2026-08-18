package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.exception.ResourceNotFoundException;
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
 * Channel template assignment on account CRUD (FEATURE_2608_06 / 21): create validates a supplied
 * templateId (404 when missing) and sets it; update keeps the existing assignment on null and replaces
 * on a value. The secretKey / seller resolution paths are already covered elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceAccountServiceTest {

    @Mock private MarketplaceAccountRepository repository;
    @Mock private SellerRepository sellerRepository;
    @Mock private ThumbnailTemplateRepository thumbnailTemplateRepository;
    @Mock private DetailTemplateRepository detailTemplateRepository;
    @InjectMocks private MarketplaceAccountServiceImpl service;

    private static final Long SELLER_ID = 1L;

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
    void update_nullTemplateId_keepsExisting_valueReplaces() {
        ThumbnailTemplate oldThumb = ThumbnailTemplate.builder().id(1L).name("old").build();
        MarketplaceAccount existing = MarketplaceAccount.builder()
                .id(50L).seller(seller()).platform("COUPANG").vendorId("V1")
                .accessKey("ak").secretKey("sk").isActive(true)
                .thumbnailTemplate(oldThumb).detailTemplate(null).build();
        given(repository.findById(50L)).willReturn(Optional.of(existing));
        given(sellerRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
        given(detailTemplateRepository.findById(5L))
                .willReturn(Optional.of(DetailTemplate.builder().id(5L).name("d").active(true).isDefault(true).build()));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));

        // thumbnailTemplateId null → keep existing (id 1); detailTemplateId 5 → replace null with 5.
        service.update(50L, baseRequest().secretKey(null).detailTemplateId(5L).build());

        ArgumentCaptor<MarketplaceAccount> captor = ArgumentCaptor.forClass(MarketplaceAccount.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getThumbnailTemplate().getId()).isEqualTo(1L);   // kept
        assertThat(captor.getValue().getDetailTemplate().getId()).isEqualTo(5L);      // replaced
    }
}
