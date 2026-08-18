package com.pms.service;

import com.pms.common.TestJpaConfig;
import com.pms.domain.DetailTemplate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LazyInit regression (FEATURE_2608_06 / 21, {@code account.getSeller()} bug homolog): the resolver reads
 * the account's LAZY {@code thumbnailTemplate}/{@code detailTemplate} after a persistence-context clear.
 * Within the transactional boundary (the resolver's callers are @Transactional) this must resolve without
 * a LazyInitializationException.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class ChannelTemplateResolverLazyInitTest {

    @Autowired private MarketplaceAccountRepository accountRepository;
    @Autowired private ThumbnailTemplateRepository thumbnailTemplateRepository;
    @Autowired private DetailTemplateRepository detailTemplateRepository;
    @Autowired private TestEntityManager em;

    @Test
    void resolvesAssignedTemplates_afterClear_withoutLazyInitException() {
        Seller seller = Seller.builder().sellerName("셀러").businessRegistration("111-22-33333").build();
        em.persist(seller);
        ThumbnailTemplate thumb = em.persist(ThumbnailTemplate.builder()
                .name("지정썸네일").canvasWidth(1000).canvasHeight(1000)
                .backgroundMode(com.pms.domain.BackgroundMode.WHITE).active(true).isDefault(false).build());
        DetailTemplate detail = em.persist(DetailTemplate.builder()
                .name("지정상세").active(true).isDefault(false).build());
        em.persist(MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").vendorId("V1").accessKey("ak").secretKey("sk")
                .isActive(true).thumbnailTemplate(thumb).detailTemplate(detail).build());
        em.flush();
        em.clear();

        ChannelTemplateResolver resolver = new ChannelTemplateResolver(
                accountRepository, thumbnailTemplateRepository, detailTemplateRepository);
        // Transient cell carrying the persisted seller id + platform (the resolver only reads these).
        ProductListing cell = ProductListing.builder().id(1L).platform("COUPANG")
                .seller(Seller.builder().id(seller.getId()).build()).build();

        // Accessing a non-id field forces LAZY initialization inside the boundary.
        assertThat(resolver.resolveThumbnail(cell).getName()).isEqualTo("지정썸네일");
        assertThat(resolver.resolveDetail(cell).getName()).isEqualTo("지정상세");
    }
}
