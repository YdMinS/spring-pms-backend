package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryType;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.ReplyCapability;
import com.pms.exception.BusinessException;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.service.lotteon.LotteonCredentials;
import com.pms.service.lotteon.LotteonInquiryApi;
import com.pms.service.lotteon.LotteonKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LotteON inquiry answers (FEATURE_2611_23 / D31 · D32 · D70 · R45 · R46).
 *
 * <ul>
 *   <li>Product Q&A: 100 {@code {"ansInfo": [{trGrpCd, trNo, pdQnaNo, spdNo, sitmNo, ansCnts}]}}.</li>
 *   <li>Seller inquiry: 181 {@code {slrInqNo, ansCnts}}.</li>
 *   <li>Seller contact: 146 {@code {slrCtctGrpNo, ctctTtl, ctctCnts}} — the title is the stored contact title (D62).</li>
 *   <li>Non-receipt report: 66 {@code {odNo, odSeq, procSeq, nrcptDeclWhdrReqCnts}} — {@code procSeq} from the order
 *       mirror; no mirror → {@value #LOTTEON_LINE_MISSING_MESSAGE}. A text form of up to 200 characters (D70).</li>
 * </ul>
 * A LotteON refusal (502) and the dev / local stop (503, D10) both become {@link InquiryReplyFailedException} with
 * their own text; any other exception goes up as is (result unknown). Local DB is untouched here.
 */
@Component
@RequiredArgsConstructor
public class LotteonInquiryReplyAdapter implements InquiryReplyAdapter {

    static final String LOTTEON_LINE_MISSING_MESSAGE = "롯데ON 주문 줄 정보가 없습니다";
    /** D32: LotteON takes at least 1 character for every type. */
    private static final int MIN_LENGTH_LOTTEON = 1;

    private final LotteonInquiryApi lotteonInquiryApi;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    @Override
    public int minLength(InquiryType type) {
        return MIN_LENGTH_LOTTEON;
    }

    /** D70 · R46: a non-receipt report is a plain text of up to 200 characters; any other type the standard form. */
    @Override
    public ReplyForm replyForm(InquiryType type) {
        if (type == InquiryType.NON_DELIVERY) {
            return new ReplyForm(ReplyCapability.FORM_TEXT, List.of(), ReplyForm.NON_DELIVERY_MAX_LENGTH);
        }
        return ReplyForm.standard(type);
    }

    @Override
    public void reply(MarketplaceAccount account, CustomerInquiry inquiry, ReplyCommand command) {
        try {
            switch (inquiry.getInquiryType()) {
                case PRODUCT_QNA -> lotteonInquiryApi.replyQna(account, qnaBody(account, inquiry, command));
                case SELLER_INQUIRY -> lotteonInquiryApi.replySellerInquiry(account,
                        body("slrInqNo", inquiry.getExternalInquiryId(), "ansCnts", command.content()));
                case CALL_CENTER -> lotteonInquiryApi.replySellerContact(account, sellerContactBody(inquiry, command));
                case NON_DELIVERY -> lotteonInquiryApi.withdrawNonReceipt(account, nonReceiptBody(account, inquiry,
                        command));
            }
        } catch (BusinessException e) {
            throw new InquiryReplyFailedException(e.getMessage());
        }
    }

    private static Map<String, Object> qnaBody(MarketplaceAccount account, CustomerInquiry inquiry,
                                               ReplyCommand command) {
        LotteonKey key = LotteonCredentials.of(account);
        Map<String, Object> line = body("trGrpCd", key.trGrpCd(), "trNo", key.trNo());
        putIfPresent(line, "pdQnaNo", inquiry.getExternalInquiryId());
        putIfPresent(line, "spdNo", inquiry.getExternalProductId());
        putIfPresent(line, "sitmNo", inquiry.getExternalItemId());
        putIfPresent(line, "ansCnts", command.content());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ansInfo", List.of(line));
        return body;
    }

    private static Map<String, Object> sellerContactBody(CustomerInquiry inquiry, ReplyCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, "slrCtctGrpNo", inquiry.getExternalInquiryId());
        body.put("ctctTtl", inquiry.getTitle() == null ? "" : inquiry.getTitle());
        putIfPresent(body, "ctctCnts", command.content());
        return body;
    }

    private Map<String, Object> nonReceiptBody(MarketplaceAccount account, CustomerInquiry inquiry,
                                               ReplyCommand command) {
        LotteonOrderLine mirror = lotteonOrderLineRepository
                .findByMarketplaceAccount_IdAndOdNoAndOdSeq(account.getId(), inquiry.getExternalOrderId(),
                        inquiry.getExternalItemId())
                .orElseThrow(() -> new InquiryReplyFailedException(LOTTEON_LINE_MISSING_MESSAGE));
        Map<String, Object> body = body("odNo", inquiry.getExternalOrderId(), "odSeq", inquiry.getExternalItemId());
        putIfPresent(body, "procSeq", mirror.getProcSeq());
        putIfPresent(body, "nrcptDeclWhdrReqCnts", command.content());
        return body;
    }

    private static Map<String, Object> body(String field1, Object value1, String field2, Object value2) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, field1, value1);
        putIfPresent(body, field2, value2);
        return body;
    }

    private static void putIfPresent(Map<String, Object> body, String field, Object value) {
        if (value != null) {
            body.put(field, value);
        }
    }
}
