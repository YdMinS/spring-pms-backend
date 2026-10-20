package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link InquiryRecord} → {@code customer_inquiry} idempotent upsert + order · cell link (FEATURE_2609_23).
 *
 * ⚠️ {@code REQUIRES_NEW} — the callers ({@code CoupangInquiryAdapter} · {@code ElevenstInquiryAdapter},
 * FEATURE_2610_17 / D16) are external HTTP loops without a transaction, and joining the facade's transaction would
 * let an inquiry load failure roll back orders and claims too (same shape as {@code ClaimUpserter}).
 *
 * ⚠️ The {@code CustomerInquiry.replies} collection is <b>never initialized</b> — replies are handled only through
 * {@link CustomerInquiryReplyRepository}. The header is rebuilt with {@code toBuilder()} and saved; an initialized
 * collection could be taken as a replaced orphanRemoval collection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryUpserter {

    /** 답변 전송(04)이 성공 직후 넣는 로컬 임시 행의 식별자 접두. */
    private static final String LOCAL_REPLY_PREFIX = "local-";

    private final CustomerInquiryRepository customerInquiryRepository;
    private final CustomerInquiryReplyRepository customerInquiryReplyRepository;

    /**
     * Saves one inquiry. Idempotent by UNIQUE(account, inquiryType, inquiryId); when no value changed, {@code save} is
     * not called (no needless UPDATE · modified_date bump).
     *
     * <p>A failed order match still saves the inquiry (D15) — product Q&A is mostly asked without an order, so no link
     * is normal.
     *
     * @param adapter the marketplace adapter that read this record — it links the listing and the order line
     *                (FEATURE_2610_23 / D12 · D21)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(MarketplaceAccount account, InquiryRecord record, InquirySyncAdapter adapter) {
        Optional<CustomerInquiry> found = customerInquiryRepository
                .findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(
                        account.getId(), record.type(), record.externalInquiryId());
        LocalDateTime now = LocalDateTime.now();

        InquirySyncAdapter.ListingMatch match = adapter.matchListing(account, record);
        ProductListingOption option = match.option();
        ProductListing listing = match.listing();
        String itemName = itemName(record, option);

        CustomerInquiry inquiry;
        if (found.isEmpty()) {
            inquiry = customerInquiryRepository.save(CustomerInquiry.builder()
                    .marketplaceAccount(account)
                    .platform(account.getPlatform())
                    .inquiryType(record.type())
                    .externalInquiryId(record.externalInquiryId())
                    .externalItemId(record.externalItemId())
                    .externalOrderId(record.externalOrderId())
                    .externalProductId(record.externalProductId())
                    .productListing(listing)
                    .orderLine(adapter.matchOrderLine(account, record))
                    .itemName(itemName)
                    .content(record.content())
                    .category(record.category())
                    .status(record.status())
                    .platformStatus(record.platformStatus())
                    .inquiredAt(record.inquiredAt())
                    .answeredAt(record.answeredAt())
                    .lastSyncedAt(now)
                    .build());
        } else {
            inquiry = update(found.get(), account, record, listing, itemName, now, adapter);
        }

        syncReplies(inquiry, record.replies());
    }

    /**
     * 기존 행 갱신 — 이미 붙은 주문·셀은 재매칭하지 않는다(연결은 한 번 붙으면 유지된다).
     * {@code inquiredAt}·{@code externalInquiryId} 는 불변이다(슬라이스 기준이라 흔들리면 안 된다).
     */
    private CustomerInquiry update(CustomerInquiry existing, MarketplaceAccount account,
                                   InquiryRecord record, ProductListing listing,
                                   String itemName, LocalDateTime now, InquirySyncAdapter adapter) {
        OrderLine orderLine = (existing.getOrderLine() != null)
                ? existing.getOrderLine()
                : adapter.matchOrderLine(account, record);
        ProductListing resolvedListing = (existing.getProductListing() != null)
                ? existing.getProductListing()
                : listing;
        String resolvedItemName = (itemName != null) ? itemName : existing.getItemName();

        if (!hasChanges(existing, record, orderLine, resolvedListing, resolvedItemName)) {
            return existing;
        }

        return customerInquiryRepository.save(existing.toBuilder()
                .orderLine(orderLine)
                .productListing(resolvedListing)
                .externalOrderId(record.externalOrderId() != null
                        ? record.externalOrderId() : existing.getExternalOrderId())
                .itemName(resolvedItemName)
                .content(record.content())
                .category(record.category())
                .status(record.status())
                .platformStatus(record.platformStatus())
                .answeredAt(record.answeredAt())
                .lastSyncedAt(now)
                .build());
    }

    /** 갱신 대상 중 하나라도 달라졌는가 — lastSyncedAt 만 바꾸려고 UPDATE 를 쏘지 않기 위한 판정. */
    private boolean hasChanges(CustomerInquiry existing, InquiryRecord record, OrderLine orderLine,
                               ProductListing listing, String itemName) {
        return !Objects.equals(existing.getOrderLine(), orderLine)
                || !Objects.equals(existing.getProductListing(), listing)
                || (record.externalOrderId() != null
                        && !Objects.equals(existing.getExternalOrderId(), record.externalOrderId()))
                || !Objects.equals(existing.getItemName(), itemName)
                || !Objects.equals(existing.getContent(), record.content())
                || !Objects.equals(existing.getCategory(), record.category())
                || existing.getStatus() != record.status()
                || !Objects.equals(existing.getPlatformStatus(), record.platformStatus())
                || !Objects.equals(existing.getAnsweredAt(), record.answeredAt());
    }

    /** 상품명은 응답 값이 우선, 없으면 연결된 셀/옵션 이름으로 채운다(화면 공백 방지). */
    private String itemName(InquiryRecord record, ProductListingOption option) {
        if (record.itemName() != null) {
            return record.itemName();
        }
        if (option == null) {
            return null;
        }
        ProductListing listing = option.getProductListing();
        return (listing != null) ? listing.getName() : option.getOptionName();
    }

    /**
     * 답변 스레드 멱등 반영 — {@code external_reply_id} 기준 추가/갱신만 한다(삭제 없음).
     *
     * <p>예외 하나: 답변 전송(04)이 성공 직후 넣는 로컬 임시 행({@code author_role = SELLER} +
     * {@code external_reply_id} 가 {@code local-} 로 시작)은 <b>내용이 같은</b> 진짜 답변이 실려오면
     * 제거한다 — 안 지우면 같은 답변이 두 줄로 보인다. 내용이 <b>다른</b> 로컬 행은 남긴다: 전송은 됐는데
     * 아직 안 실려온 건일 수 있고, 지우면 사용자가 보낸 글이 화면에서 사라진다.
     */
    private void syncReplies(CustomerInquiry inquiry, List<InquiryRecord.ReplyRecord> replies) {
        if (replies == null || replies.isEmpty()) {
            return;
        }
        List<CustomerInquiryReply> existing =
                customerInquiryReplyRepository.findByInquiry_IdOrderByRepliedAtAsc(inquiry.getId());
        Map<String, CustomerInquiryReply> byExternalId = existing.stream()
                .filter(reply -> reply.getExternalReplyId() != null)
                .collect(Collectors.toMap(CustomerInquiryReply::getExternalReplyId,
                        Function.identity(), (first, second) -> first));

        for (InquiryRecord.ReplyRecord record : replies) {
            if (record.externalReplyId() == null) {
                continue;               // 식별자가 없으면 멱등할 수 없다 — 중복 적재보다 건너뛰는 쪽
            }
            CustomerInquiryReply found = byExternalId.get(record.externalReplyId());
            if (found == null) {
                customerInquiryReplyRepository.save(CustomerInquiryReply.builder()
                        .inquiry(inquiry)
                        .externalReplyId(record.externalReplyId())
                        .parentExternalReplyId(record.parentExternalReplyId())
                        .authorRole(record.authorRole())
                        .authorName(record.authorName())
                        .content(record.content())
                        .transferStatus(record.transferStatus())
                        .repliedAt(record.repliedAt())
                        .build());
            } else if (hasChanges(found, record)) {
                customerInquiryReplyRepository.save(found.toBuilder()
                        .parentExternalReplyId(record.parentExternalReplyId())
                        .authorRole(record.authorRole())
                        .authorName(record.authorName())
                        .content(record.content())
                        .transferStatus(record.transferStatus())
                        .repliedAt(record.repliedAt())
                        .build());
            }
        }

        removeSupersededLocalReplies(existing, replies);
    }

    private boolean hasChanges(CustomerInquiryReply existing, InquiryRecord.ReplyRecord record) {
        return !Objects.equals(existing.getParentExternalReplyId(), record.parentExternalReplyId())
                || existing.getAuthorRole() != record.authorRole()
                || !Objects.equals(existing.getAuthorName(), record.authorName())
                || !Objects.equals(existing.getContent(), record.content())
                || !Objects.equals(existing.getTransferStatus(), record.transferStatus())
                || !Objects.equals(existing.getRepliedAt(), record.repliedAt());
    }

    /** 내용이 같은 진짜 답변이 도착한 로컬 임시 행만 지운다(04 Step 4). 내용이 다르면 남긴다. */
    private void removeSupersededLocalReplies(List<CustomerInquiryReply> existing,
                                              List<InquiryRecord.ReplyRecord> replies) {
        List<CustomerInquiryReply> superseded = existing.stream()
                .filter(reply -> reply.getAuthorRole() == InquiryAuthorRole.SELLER)
                .filter(reply -> reply.getExternalReplyId() != null
                        && reply.getExternalReplyId().startsWith(LOCAL_REPLY_PREFIX))
                .filter(reply -> replies.stream().anyMatch(record -> sameContent(reply.getContent(), record.content())))
                .toList();
        if (!superseded.isEmpty()) {
            customerInquiryReplyRepository.deleteAll(superseded);
        }
    }

    /** 양쪽 strip 후 비교 — 플랫폼이 앞뒤 공백을 다듬어 돌려주는 경우가 있다. */
    private boolean sameContent(String local, String incoming) {
        if (local == null || incoming == null) {
            return false;
        }
        return local.strip().equals(incoming.strip());
    }
}
