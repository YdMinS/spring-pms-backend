package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CustomerInquiryRepository — nullable 파라미터 필터 쿼리와 앵커 조회.
 *
 * 필터가 6개라 조합별 명명 메서드 대신 {@code (:param is null or ...)} 쿼리 <b>하나</b>로 조립했다.
 * 그 방식이 실제로 걸러지는지는 목으로 검증되지 않으므로 여기서만 본다.
 *
 * <p>{@code toBuilder()} 재조립 저장도 함께 고정한다 — {@code CustomerInquiry} 는 orphanRemoval
 * 컬렉션을 갖고 있어, 쓰기 경로가 그 컬렉션을 초기화하면 갱신 저장이 깨질 수 있다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class CustomerInquiryRepositoryTest {

    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 9, 10, 0, 0);

    @Autowired private CustomerInquiryRepository customerInquiryRepository;
    @Autowired private CustomerInquiryReplyRepository customerInquiryReplyRepository;
    @Autowired private TestEntityManager em;

    private MarketplaceAccount accountA;
    private MarketplaceAccount accountB;

    @BeforeEach
    void setUp() {
        accountA = persistAccount("셀러A", "111-11-11111", "V-A");
        accountB = persistAccount("셀러B", "222-22-22222", "V-B");
    }

    @Test
    void search_allFiltersNull_returnsEveryInquiryInWindowNewestFirst() {
        persistInquiry(accountA, InquiryType.PRODUCT_QNA, "I-1", InquiryStatus.UNANSWERED,
                LocalDateTime.of(2026, 9, 2, 10, 0), "재입고 문의", "양말", "O-1");
        persistInquiry(accountB, InquiryType.CALL_CENTER, "I-2", InquiryStatus.ANSWERED,
                LocalDateTime.of(2026, 9, 5, 10, 0), "배송 지연", "장갑", "O-2");
        // 창 밖(상한은 배타적) — 걸러져야 한다.
        persistInquiry(accountA, InquiryType.PRODUCT_QNA, "I-3", InquiryStatus.UNANSWERED,
                LocalDateTime.of(2026, 9, 10, 0, 0), "창 밖", null, null);

        List<CustomerInquiry> found = search(null, null, null, null, null);

        assertThat(found).extracting(CustomerInquiry::getExternalInquiryId)
                .containsExactly("I-2", "I-1");     // inquiredAt DESC
    }

    @Test
    void search_eachFilterNarrowsTheResult() {
        persistInquiry(accountA, InquiryType.PRODUCT_QNA, "I-1", InquiryStatus.UNANSWERED,
                LocalDateTime.of(2026, 9, 2, 10, 0), "재입고 문의", "양말", "O-1");
        persistInquiry(accountB, InquiryType.CALL_CENTER, "I-2", InquiryStatus.ANSWERED,
                LocalDateTime.of(2026, 9, 5, 10, 0), "배송 지연", "장갑", "O-2");

        assertThat(search(InquiryType.CALL_CENTER, null, null, null, null))
                .extracting(CustomerInquiry::getExternalInquiryId).containsExactly("I-2");
        assertThat(search(null, InquiryStatus.UNANSWERED, null, null, null))
                .extracting(CustomerInquiry::getExternalInquiryId).containsExactly("I-1");
        assertThat(search(null, null, accountA.getId(), null, null))
                .extracting(CustomerInquiry::getExternalInquiryId).containsExactly("I-1");
        assertThat(search(null, null, null, accountB.getSeller().getId(), null))
                .extracting(CustomerInquiry::getExternalInquiryId).containsExactly("I-2");
        // accountId 와 sellerId 는 함께 올 수 있다(AND) — 서로 다른 판매자면 결과가 비어야 한다.
        assertThat(search(null, null, accountA.getId(), accountB.getSeller().getId(), null)).isEmpty();
    }

    @Test
    void search_keywordMatchesContentItemNameOrOrderId() {
        persistInquiry(accountA, InquiryType.PRODUCT_QNA, "I-1", InquiryStatus.UNANSWERED,
                LocalDateTime.of(2026, 9, 2, 10, 0), "재입고 문의", "양말", "O-1");
        persistInquiry(accountB, InquiryType.CALL_CENTER, "I-2", InquiryStatus.ANSWERED,
                LocalDateTime.of(2026, 9, 5, 10, 0), "배송 지연", "장갑", "O-2");

        assertThat(search(null, null, null, null, "재입고"))
                .extracting(CustomerInquiry::getExternalInquiryId).containsExactly("I-1");
        assertThat(search(null, null, null, null, "장갑"))
                .extracting(CustomerInquiry::getExternalInquiryId).containsExactly("I-2");
        assertThat(search(null, null, null, null, "O-2"))
                .extracting(CustomerInquiry::getExternalInquiryId).containsExactly("I-2");
        assertThat(search(null, null, null, null, "없는말")).isEmpty();
    }

    @Test
    void findTopByStatusOrderByInquiredAtAsc_returnsOldestUnansweredOfThatAccount() {
        persistInquiry(accountA, InquiryType.PRODUCT_QNA, "I-old", InquiryStatus.UNANSWERED,
                LocalDateTime.of(2026, 9, 1, 10, 0), "오래된 미답변", null, null);
        persistInquiry(accountA, InquiryType.PRODUCT_QNA, "I-new", InquiryStatus.UNANSWERED,
                LocalDateTime.of(2026, 9, 8, 10, 0), "최근 미답변", null, null);
        persistInquiry(accountB, InquiryType.PRODUCT_QNA, "I-other", InquiryStatus.UNANSWERED,
                LocalDateTime.of(2026, 8, 1, 10, 0), "다른 계정", null, null);

        assertThat(customerInquiryRepository.findTopByMarketplaceAccount_IdAndStatusOrderByInquiredAtAsc(
                accountA.getId(), InquiryStatus.UNANSWERED))
                .get().extracting(CustomerInquiry::getExternalInquiryId).isEqualTo("I-old");
    }

    @Test
    void save_afterToBuilder_updatesStatusWithoutBreakingTheReplyCollection() {
        CustomerInquiry inquiry = persistInquiry(accountA, InquiryType.PRODUCT_QNA, "I-1",
                InquiryStatus.UNANSWERED, LocalDateTime.of(2026, 9, 2, 10, 0), "본문", null, null);
        customerInquiryReplyRepository.save(CustomerInquiryReply.builder()
                .inquiry(inquiry)
                .externalReplyId("R-1")
                .authorRole(InquiryAuthorRole.SELLER)
                .content("답변")
                .repliedAt(LocalDateTime.of(2026, 9, 2, 12, 0))
                .build());
        em.flush();
        em.clear();

        CustomerInquiry loaded = customerInquiryRepository.findById(inquiry.getId()).orElseThrow();
        customerInquiryRepository.save(loaded.toBuilder().status(InquiryStatus.STALE).build());
        em.flush();
        em.clear();

        assertThat(customerInquiryRepository.findById(inquiry.getId()).orElseThrow().getStatus())
                .isEqualTo(InquiryStatus.STALE);
        assertThat(customerInquiryReplyRepository.findByInquiry_IdOrderByRepliedAtAsc(inquiry.getId()))
                .hasSize(1);
    }

    private List<CustomerInquiry> search(InquiryType type, InquiryStatus status, Long accountId,
                                         Long sellerId, String keyword) {
        return customerInquiryRepository.search(type, status, accountId, sellerId,
                WINDOW_START, WINDOW_END, keyword);
    }

    private MarketplaceAccount persistAccount(String sellerName, String bizReg, String vendorId) {
        Seller seller = Seller.builder().sellerName(sellerName).businessRegistration(bizReg).build();
        em.persist(seller);
        MarketplaceAccount account = MarketplaceAccount.builder()
                .seller(seller).platform(Platform.COUPANG).vendorId(vendorId)
                .accessKey("ak").secretKey("sk").isActive(true).build();
        em.persist(account);
        return account;
    }

    private CustomerInquiry persistInquiry(MarketplaceAccount account, InquiryType type, String externalId,
                                           InquiryStatus status, LocalDateTime inquiredAt, String content,
                                           String itemName, String externalOrderId) {
        CustomerInquiry inquiry = CustomerInquiry.builder()
                .marketplaceAccount(account)
                .platform(Platform.COUPANG)
                .inquiryType(type)
                .externalInquiryId(externalId)
                .externalOrderId(externalOrderId)
                .itemName(itemName)
                .content(content)
                .status(status)
                .platformStatus("RAW")
                .inquiredAt(inquiredAt)
                .lastSyncedAt(inquiredAt)
                .build();
        em.persist(inquiry);
        em.flush();
        return inquiry;
    }
}
