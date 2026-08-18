package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Channel template resolution (FEATURE_2608_06 / 21): the account's assigned template wins; a missing
 * account or a null FK falls back to the tenant default; neither present throws. Detail is logically
 * identical to thumbnail, so only the representative fallback case is repeated for it.
 */
@ExtendWith(MockitoExtension.class)
class ChannelTemplateResolverTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ThumbnailTemplateRepository thumbnailTemplateRepository;
    @Mock private DetailTemplateRepository detailTemplateRepository;
    @InjectMocks private ChannelTemplateResolver resolver;

    private static final Long SELLER_ID = 1L;
    private static final String PLATFORM = "COUPANG";

    private ProductListing cell() {
        return ProductListing.builder().id(100L).platform(PLATFORM)
                .seller(Seller.builder().id(SELLER_ID).build()).build();
    }

    private MarketplaceAccount account(ThumbnailTemplate thumb, DetailTemplate detail) {
        return MarketplaceAccount.builder()
                .id(7L).platform(PLATFORM).thumbnailTemplate(thumb).detailTemplate(detail).build();
    }

    // ---- thumbnail ----

    @Test
    void resolveThumbnail_accountHasTemplate_returnsAssigned() {
        ThumbnailTemplate assigned = ThumbnailTemplate.builder().id(9L).name("지정").build();
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(account(assigned, null)));

        assertThat(resolver.resolveThumbnail(cell()).getId()).isEqualTo(9L);
    }

    @Test
    void resolveThumbnail_noAccount_fallsBackToTenantDefault() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.empty());
        given(thumbnailTemplateRepository.findByIsDefaultTrueAndActiveTrue())
                .willReturn(Optional.of(ThumbnailTemplate.builder().id(3L).name("기본").build()));

        assertThat(resolver.resolveThumbnail(cell()).getId()).isEqualTo(3L);
    }

    @Test
    void resolveThumbnail_accountWithNullTemplate_fallsBackToTenantDefault() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.of(account(null, null)));
        given(thumbnailTemplateRepository.findByIsDefaultTrueAndActiveTrue())
                .willReturn(Optional.of(ThumbnailTemplate.builder().id(3L).name("기본").build()));

        assertThat(resolver.resolveThumbnail(cell()).getId()).isEqualTo(3L);
    }

    @Test
    void resolveThumbnail_noAccountAndNoDefault_throws() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.empty());
        given(thumbnailTemplateRepository.findByIsDefaultTrueAndActiveTrue()).willReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveThumbnail(cell()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- detail (fallback representative) ----

    @Test
    void resolveDetail_noAccount_fallsBackToTenantDefault() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, PLATFORM))
                .willReturn(Optional.empty());
        given(detailTemplateRepository.findByIsDefaultTrueAndActiveTrue())
                .willReturn(Optional.of(DetailTemplate.builder().id(5L).name("기본").active(true).isDefault(true).build()));

        assertThat(resolver.resolveDetail(cell()).getId()).isEqualTo(5L);
    }
}
