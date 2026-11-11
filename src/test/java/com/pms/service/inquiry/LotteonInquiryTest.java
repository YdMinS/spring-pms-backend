package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.LotteonAccountCredential;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.dto.response.InquiryTypeCatalogResponse;
import com.pms.dto.response.ReplyCapability;
import com.pms.exception.BusinessException;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonInquiryApi;
import com.pms.service.lotteon.LotteonPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * FEATURE_2611_23 / 08 — LotteON customer inquiries: the calls and their result rules (R40 · R41 · D81 · D83), the
 * four record mappings (R42 · R47 · D82 · D84), the sync loop, the links (R43), the reply form (D70 · R46), the
 * answers (R45), the type catalog (D31) and the title column (R44).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LotteonInquiryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);

    @Mock private LotteonApiClient lotteonApiClient;
    @Mock private LotteonInquiryApi lotteonInquiryApi;
    @Mock private InquiryUpserter inquiryUpserter;
    @Mock private InquiryStaleSweeper inquiryStaleSweeper;
    @Mock private LotteonOrderLineRepository lotteonOrderLineRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private CustomerInquiryRepository customerInquiryRepository;
    @Mock private CustomerInquiryReplyRepository customerInquiryReplyRepository;

    private final MarketplaceAccount account = MarketplaceAccount.builder().id(9L).platform(Platform.LOTTEON)
            .lotteonCredential(LotteonAccountCredential.builder().apiKey("KEY").trGrpCd("SR").trNo("T1").build())
            .build();

    // ---------------------------------------------------------------- fixtures

    private LotteonInquiryAdapter adapter() {
        return new LotteonInquiryAdapter(lotteonInquiryApi, inquiryUpserter, inquiryStaleSweeper,
                lotteonOrderLineRepository, productListingRepository, productListingOptionRepository,
                customerInquiryRepository);
    }

    private LotteonInquiryReplyAdapter replyAdapter() {
        return new LotteonInquiryReplyAdapter(lotteonInquiryApi, lotteonOrderLineRepository);
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode dataOf(int rows) {
        ObjectNode answer = JSON.createObjectNode();
        answer.put("returnCode", "0000");
        ArrayNode data = answer.putArray("data");
        for (int i = 0; i < rows; i++) {
            data.addObject().put("pdQnaNo", String.valueOf(i));
        }
        return answer;
    }

    private CustomerInquiry inquiry(InquiryType type, String id) {
        return CustomerInquiry.builder().id(1L).marketplaceAccount(account).platform(Platform.LOTTEON)
                .inquiryType(type).externalInquiryId(id).status(InquiryStatus.UNANSWERED).platformStatus("")
                .inquiredAt(NOW).lastSyncedAt(NOW).build();
    }

    private static LotteonOrderLine mirror(long orderLineId, String odSeq, String sitmNo) {
        return LotteonOrderLine.builder().id(orderLineId).orderLine(OrderLine.builder().id(orderLineId).build())
                .odNo("OD1").odSeq(odSeq).sitmNo(sitmNo).build();
    }

    // ---------------------------------------------------------------- API (R40 · R41)

    @Test
    void qnaList_callsWithAllAndReadsTheNextPageOnlyWhenFull() {
        LotteonInquiryApi api = new LotteonInquiryApi(lotteonApiClient);
        given(lotteonApiClient.post(eq(LotteonPaths.QNA_LIST), any(), eq("KEY")))
                .willReturn(dataOf(100), dataOf(3));

        List<JsonNode> rows = api.qnaList(account, NOW.minusDays(6), NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(lotteonApiClient, times(2)).post(eq(LotteonPaths.QNA_LIST), body.capture(), eq("KEY"));
        Map<String, Object> first = body.getAllValues().get(0);
        assertThat(first.get("qnaStatCd")).isEqualTo("ALL");
        assertThat(first.get("trGrpCd")).isEqualTo("SR");
        assertThat(first.get("trNo")).isEqualTo("T1");
        assertThat(first.get("pageNo")).isEqualTo(1);
        assertThat(first.get("rowsPerPage")).isEqualTo(100);
        assertThat(body.getAllValues().get(1).get("pageNo")).isEqualTo(2);
        assertThat(rows).hasSize(103);
    }

    @Test
    void sellerContacts_rsltCdDecidesSuccessEmptyAndFailure() {
        LotteonInquiryApi api = new LotteonInquiryApi(lotteonApiClient);
        given(lotteonApiClient.post(eq(LotteonPaths.SELLER_CONTACT_LIST), any(), eq("KEY")))
                .willReturn(json("{\"rsltCd\":\"3000\",\"rsltMsg\":\"조회 내역이 없습니다.\"}"),
                        json("{\"rsltCd\":\"2006\",\"rsltMsg\":\"조회기간 오류\"}"));

        assertThat(api.sellerContacts(account, NOW.toLocalDate().minusDays(6), NOW.toLocalDate())).isEmpty();
        assertThatThrownBy(() -> api.sellerContacts(account, NOW.toLocalDate().minusDays(6), NOW.toLocalDate()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("조회기간 오류")
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void replySellerContact_rsltCdFailureIsAFailure() {
        LotteonInquiryApi api = new LotteonInquiryApi(lotteonApiClient);
        given(lotteonApiClient.post(eq(LotteonPaths.SELLER_CONTACT_REPLY), any(), eq("KEY")))
                .willReturn(json("{\"rsltCd\":\"1001\",\"rslt\":\"제목 누락\"}"));

        assertThatThrownBy(() -> api.replySellerContact(account, Map.of("slrCtctGrpNo", "11586")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("제목 누락")
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ---------------------------------------------------------------- records (R42 · R47 · D82 · D84)

    @Test
    void qna_mapsTheThreeStatuses() {
        String row = "{\"pdQnaNo\":\"307\",\"qstTtl\":null,\"qstCnts\":\"사이즈가 어떻게 되나요\","
                + "\"regDttm\":\"20261101100000\",\"spdNo\":\"LO1\",\"sitmNo\":\"LO1_1\",\"qnaStatCd\":\"%s\"}";
        List<String> codes = List.of("NPROC", "PROC", "CC_TCTL");
        List<InquiryStatus> expected = List.of(InquiryStatus.UNANSWERED, InquiryStatus.ANSWERED, InquiryStatus.CLOSED);

        for (int i = 0; i < codes.size(); i++) {
            InquiryRecord record = LotteonInquiryAdapter.qna(json(row.formatted(codes.get(i))), NOW);

            assertThat(record.type()).isEqualTo(InquiryType.PRODUCT_QNA);
            assertThat(record.externalInquiryId()).isEqualTo("307");
            assertThat(record.externalProductId()).isEqualTo("LO1");
            assertThat(record.externalItemId()).isEqualTo("LO1_1");
            assertThat(record.content()).isEqualTo("사이즈가 어떻게 되나요");
            assertThat(record.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 10, 0));
            assertThat(record.replies()).isEmpty();
            assertThat(record.status()).isEqualTo(expected.get(i));
            assertThat(record.platformStatus()).isEqualTo(codes.get(i));
        }
    }

    @Test
    void sellerInquiry_answeredRowCarriesTheSellerReply() {
        InquiryRecord record = LotteonInquiryAdapter.sellerInquiry(json("{\"slrInqNo\":\"779\","
                + "\"slrInqProcStatCd\":\"ANS\",\"inqTtl\":null,\"inqCnts\":\"반품하려면?\",\"odNo\":\"OD1\","
                + "\"spdNm\":\"물티슈\",\"sitmNm\":\"대형\",\"ansCnts\":\"접수해 주세요\","
                + "\"accpDttm\":\"20261101100000\",\"procDttm\":\"20261101110000\"}"), NOW);

        assertThat(record.type()).isEqualTo(InquiryType.SELLER_INQUIRY);
        assertThat(record.externalInquiryId()).isEqualTo("779");
        assertThat(record.externalOrderId()).isEqualTo("OD1");
        assertThat(record.itemName()).isEqualTo("물티슈 / 대형");
        assertThat(record.status()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(record.platformStatus()).isEqualTo("ANS");
        assertThat(record.replies()).singleElement().satisfies(reply -> {
            assertThat(reply.externalReplyId()).isEqualTo("779-answer");
            assertThat(reply.authorRole()).isEqualTo(InquiryAuthorRole.SELLER);
            assertThat(reply.content()).isEqualTo("접수해 주세요");
            assertThat(reply.repliedAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 11, 0));
        });
    }

    private static JsonNode headRow() {
        return json("{\"slrCtctNo\":\"11586\",\"slrCtctGrpNo\":\"11586\",\"sndDvsCd\":\"SND\","
                + "\"ctctTtl\":\"배송 지연 문의\",\"ctctCnts\":\"언제 출고되나요\",\"sndDttm\":\"20261101090000\","
                + "\"ansDttm\":null,\"odNo\":\"OD1\"}");
    }

    private static JsonNode sellerRow() {
        return json("{\"slrCtctNo\":\"11590\",\"slrCtctGrpNo\":\"11586\",\"sndDvsCd\":\"RCV\","
                + "\"ctctCnts\":\"내일 출고합니다\",\"sndDttm\":\"20261101100000\"}");
    }

    @Test
    void sellerContacts_groupsRowsUnderTheFirstContact() {
        JsonNode agent = json("{\"slrCtctNo\":\"11600\",\"slrCtctGrpNo\":\"11586\",\"sndDvsCd\":\"SND\","
                + "\"ctctCnts\":\"확인 부탁드립니다\",\"sndDttm\":\"20261101110000\",\"ansDttm\":null}");

        List<InquiryRecord> records = adapter().sellerContacts(account, List.of(headRow(), sellerRow(), agent), NOW);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.type()).isEqualTo(InquiryType.CALL_CENTER);
            assertThat(record.externalInquiryId()).isEqualTo("11586");
            assertThat(record.content()).isEqualTo("언제 출고되나요");
            assertThat(record.title()).isEqualTo("배송 지연 문의");
            assertThat(record.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 9, 0));
            assertThat(record.status()).isEqualTo(InquiryStatus.UNANSWERED);
            assertThat(record.platformStatus()).isEqualTo("SND");
            assertThat(record.replies()).extracting(InquiryRecord.ReplyRecord::externalReplyId,
                            InquiryRecord.ReplyRecord::authorRole)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("11590", InquiryAuthorRole.SELLER),
                            org.assertj.core.groups.Tuple.tuple("11600", InquiryAuthorRole.CS_AGENT));
        });
    }

    @Test
    void sellerContacts_lastRowFromTheSellerIsAnswered() {
        List<InquiryRecord> records = adapter().sellerContacts(account, List.of(headRow(), sellerRow()), NOW);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(InquiryStatus.ANSWERED);
            assertThat(record.platformStatus()).isEqualTo("RCV");
            assertThat(record.answeredAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 10, 0));
        });
    }

    @Test
    void sellerContacts_missingHeadReusesTheStoredHead() {
        JsonNode row = json("{\"slrCtctNo\":\"11590\",\"slrCtctGrpNo\":\"11586\",\"sndDvsCd\":\"RCV\","
                + "\"ctctCnts\":\"내일 출고합니다\",\"sndDttm\":\"20261102100000\"}");
        CustomerInquiry stored = inquiry(InquiryType.CALL_CENTER, "11586").toBuilder()
                .content("저장 본문").title("저장 제목").inquiredAt(LocalDateTime.of(2026, 11, 1, 9, 0)).build();
        given(customerInquiryRepository.findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(9L,
                InquiryType.CALL_CENTER, "11586")).willReturn(Optional.of(stored));

        List<InquiryRecord> records = adapter().sellerContacts(account, List.of(row), NOW);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.content()).isEqualTo("저장 본문");
            assertThat(record.title()).isEqualTo("저장 제목");
            assertThat(record.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 9, 0));
            assertThat(record.replies()).extracting(InquiryRecord.ReplyRecord::externalReplyId)
                    .containsExactly("11590");
        });
    }

    @Test
    void nonReceipt_withdrawTextMeansAnswered() {
        String row = "{\"nrcptDeclAccpNo\":\"55\",\"odNo\":\"OD1\",\"odSeq\":\"1\",\"clmRsnCd\":\"401\","
                + "\"nrcptDeclCnts\":\"안 왔어요\",\"nrcptDeclWhdrReqCnts\":%s,"
                + "\"nrcptDeclAccpDttm\":\"20261101100000\"}";

        InquiryRecord open = LotteonInquiryAdapter.nonReceipt(json(row.formatted("null")), NOW);
        InquiryRecord handled = LotteonInquiryAdapter.nonReceipt(json(row.formatted("\"재발송했습니다\"")), NOW);

        assertThat(open.type()).isEqualTo(InquiryType.NON_DELIVERY);
        assertThat(open.externalInquiryId()).isEqualTo("55");
        assertThat(open.externalItemId()).isEqualTo("1");
        assertThat(open.content()).isEqualTo("전체 상품이 오지 않음 — 안 왔어요");
        assertThat(open.status()).isEqualTo(InquiryStatus.UNANSWERED);
        assertThat(open.platformStatus()).isEqualTo("N");
        assertThat(open.replies()).isEmpty();
        assertThat(handled.status()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(handled.platformStatus()).isEqualTo("Y");
        assertThat(handled.replies()).singleElement().satisfies(reply -> {
            assertThat(reply.externalReplyId()).isEqualTo("55-answer");
            assertThat(reply.authorRole()).isEqualTo(InquiryAuthorRole.SELLER);
            assertThat(reply.content()).isEqualTo("재발송했습니다");
        });
    }

    // ---------------------------------------------------------------- sync · links (R43)

    @Test
    void syncInquiries_oneTypeFailingStillLoadsTheOthersAndRethrows() {
        BusinessException failure = new BusinessException("실패", HttpStatus.BAD_GATEWAY);
        given(lotteonInquiryApi.qnaList(eq(account), any(), any())).willThrow(failure);
        given(lotteonInquiryApi.sellerInquiries(eq(account), any(), any())).willReturn(List.of(json(
                "{\"slrInqNo\":\"779\",\"slrInqProcStatCd\":\"REQ\",\"inqCnts\":\"반품하려면?\"}")));
        given(lotteonInquiryApi.sellerContacts(eq(account), any(), any())).willReturn(List.of());
        given(lotteonInquiryApi.nonReceipts(eq(account), any(), any())).willReturn(List.of());
        LotteonInquiryAdapter adapter = adapter();

        assertThatThrownBy(() -> adapter.syncInquiries(account)).isSameAs(failure);

        verify(inquiryUpserter, times(1)).upsert(eq(account), any(InquiryRecord.class), eq(adapter));
        verify(lotteonInquiryApi, times(1)).sellerContacts(eq(account), any(), any());
        verify(lotteonInquiryApi, times(1)).nonReceipts(eq(account), any(), any());
        verify(inquiryStaleSweeper, times(1)).sweep(account);
    }

    @Test
    void matchOrderLine_linksOnlyAnUnambiguousLine() {
        given(lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNo(9L, "OD1"))
                .willReturn(List.of(mirror(1L, "1", "S1"), mirror(2L, "2", "S2")));
        InquiryRecord sellerInquiry = new InquiryRecord(InquiryType.SELLER_INQUIRY, "779", "S2", "OD1", null, null,
                null, null, InquiryStatus.UNANSWERED, "", NOW, null, List.of());
        InquiryRecord contact = new InquiryRecord(InquiryType.CALL_CENTER, "11586", null, "OD1", null, null,
                null, null, InquiryStatus.UNANSWERED, "", NOW, null, List.of());

        OrderLine first = adapter().matchOrderLine(account, sellerInquiry);
        OrderLine second = adapter().matchOrderLine(account, contact);

        assertThat(first.getId()).isEqualTo(2L);
        assertThat(second).isNull();
    }

    @Test
    void matchListing_nonDeliveryLinksByProductNumberOnly() {
        ProductListing listing = ProductListing.builder().id(70L).build();
        given(productListingRepository.findLotteonListings(9L, "LO1")).willReturn(List.of(listing));
        InquiryRecord record = new InquiryRecord(InquiryType.NON_DELIVERY, "55", "1", "OD1", "LO1", null, null, null,
                InquiryStatus.UNANSWERED, "N", NOW, null, List.of());

        InquirySyncAdapter.ListingMatch match = adapter().matchListing(account, record);

        assertThat(match.listing()).isSameAs(listing);
        assertThat(match.option()).isNull();
        verify(productListingOptionRepository, never()).findLotteonOptionsByMarketId(anyLong(), anyString());
    }

    // ---------------------------------------------------------------- reply form · answers (D70 · R45 · R46)

    @Test
    void replyForm_lotteonNonDeliveryIsTextUpTo200() {
        InquiryReplyPolicy policy = new InquiryReplyPolicy(
                List.of(replyAdapter(), new ElevenstInquiryReplyAdapter(null)), mock(CarrierCodeService.class));
        CustomerInquiry lotteon = inquiry(InquiryType.NON_DELIVERY, "55");
        CustomerInquiry elevenst = lotteon.toBuilder().platform(Platform.ELEVENST).build();

        ReplyCapability lotteonForm = policy.evaluate(lotteon, List.of());
        ReplyCapability elevenstForm = policy.evaluate(elevenst, List.of());

        assertThat(lotteonForm.form()).isEqualTo(ReplyCapability.FORM_TEXT);
        assertThat(lotteonForm.reasons()).isEmpty();
        assertThat(lotteonForm.maxLength()).isEqualTo(200);
        assertThat(lotteonForm.minLength()).isEqualTo(1);
        assertThat(elevenstForm.form()).isEqualTo(ReplyCapability.FORM_NON_DELIVERY);
        assertThat(elevenstForm.reasons()).hasSize(4);
        assertThat(elevenstForm.maxLength()).isEqualTo(200);
    }

    @SuppressWarnings("unchecked")
    @Test
    void reply_productQnaSends100WithTheSellerKey() {
        CustomerInquiry qna = inquiry(InquiryType.PRODUCT_QNA, "307").toBuilder()
                .externalProductId("LO1").externalItemId("LO1_1").build();

        replyAdapter().reply(account, qna, new InquiryReplyAdapter.ReplyCommand("S 사이즈입니다", null));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(lotteonInquiryApi).replyQna(eq(account), body.capture());
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.getValue().get("ansInfo");
        assertThat(lines.get(0)).containsExactlyInAnyOrderEntriesOf(Map.of("trGrpCd", "SR", "trNo", "T1",
                "pdQnaNo", "307", "spdNo", "LO1", "sitmNo", "LO1_1", "ansCnts", "S 사이즈입니다"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void reply_sellerContactSends146WithTheStoredTitle() {
        CustomerInquiry contact = inquiry(InquiryType.CALL_CENTER, "11586").toBuilder().title("배송 지연 문의").build();

        replyAdapter().reply(account, contact, new InquiryReplyAdapter.ReplyCommand("내일 출고합니다", null));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(lotteonInquiryApi).replySellerContact(eq(account), body.capture());
        assertThat(body.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of("slrCtctGrpNo", "11586",
                "ctctTtl", "배송 지연 문의", "ctctCnts", "내일 출고합니다"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void reply_nonDeliverySends66WithTheMirrorProcSeq() {
        CustomerInquiry report = inquiry(InquiryType.NON_DELIVERY, "55").toBuilder()
                .externalOrderId("OD1").externalItemId("1").build();
        given(lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD1", "1"))
                .willReturn(Optional.of(mirror(1L, "1", "S1").toBuilder().procSeq("2").build()));

        replyAdapter().reply(account, report, new InquiryReplyAdapter.ReplyCommand("재발송했습니다", null));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(lotteonInquiryApi).withdrawNonReceipt(eq(account), body.capture());
        assertThat(body.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of("odNo", "OD1", "odSeq", "1",
                "procSeq", "2", "nrcptDeclWhdrReqCnts", "재발송했습니다"));
    }

    @Test
    void reply_lotteonRefusalAndDevStopAreRefusals() {
        CustomerInquiry question = inquiry(InquiryType.SELLER_INQUIRY, "779");
        willThrow(new BusinessException("답변내용은 필수입니다", HttpStatus.BAD_GATEWAY),
                new BusinessException("개발 서버에서는 롯데ON 을 부르지 않습니다", HttpStatus.SERVICE_UNAVAILABLE))
                .given(lotteonInquiryApi).replySellerInquiry(eq(account), anyMap());
        LotteonInquiryReplyAdapter adapter = replyAdapter();
        InquiryReplyAdapter.ReplyCommand command = new InquiryReplyAdapter.ReplyCommand("답", null);

        assertThatThrownBy(() -> adapter.reply(account, question, command))
                .isInstanceOf(InquiryReplyFailedException.class).hasMessage("답변내용은 필수입니다");
        assertThatThrownBy(() -> adapter.reply(account, question, command))
                .isInstanceOf(InquiryReplyFailedException.class).hasMessage("개발 서버에서는 롯데ON 을 부르지 않습니다");
    }

    // ---------------------------------------------------------------- catalog · title (D31 · R44)

    @Test
    void catalog_listsTheFourLotteonTypes() {
        List<InquiryTypeCatalogResponse> catalog = new InquiryTypeCatalog().forPlatforms(List.of(Platform.LOTTEON));

        assertThat(catalog).singleElement().satisfies(entry ->
                assertThat(entry.types()).extracting(InquiryTypeCatalogResponse.Option::code,
                                InquiryTypeCatalogResponse.Option::label)
                        .containsExactly(
                                org.assertj.core.groups.Tuple.tuple(InquiryType.PRODUCT_QNA, "상품 Q&A"),
                                org.assertj.core.groups.Tuple.tuple(InquiryType.SELLER_INQUIRY, "판매자 문의"),
                                org.assertj.core.groups.Tuple.tuple(InquiryType.CALL_CENTER, "판매자 연락"),
                                org.assertj.core.groups.Tuple.tuple(InquiryType.NON_DELIVERY, "미수령 신고")));
    }

    @Test
    void upserter_writesTheTitleAndKeepsItWhenAbsent() {
        InquiryUpserter upserter = new InquiryUpserter(customerInquiryRepository, customerInquiryReplyRepository);
        InquirySyncAdapter linker = mock(InquirySyncAdapter.class);
        given(linker.matchListing(any(), any())).willReturn(new InquirySyncAdapter.ListingMatch(null, null));
        given(customerInquiryRepository.save(any(CustomerInquiry.class))).will(returnsFirstArg());
        InquiryRecord fresh = new InquiryRecord(InquiryType.CALL_CENTER, "11586", null, "OD1", null, null, "본문", null,
                InquiryStatus.UNANSWERED, "SND", NOW, null, List.of(), "배송 지연 문의");
        given(customerInquiryRepository.findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(9L,
                InquiryType.CALL_CENTER, "11586")).willReturn(Optional.empty());

        upserter.upsert(account, fresh, linker);

        ArgumentCaptor<CustomerInquiry> saved = ArgumentCaptor.forClass(CustomerInquiry.class);
        verify(customerInquiryRepository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("배송 지연 문의");

        CustomerInquiry existing = inquiry(InquiryType.CALL_CENTER, "11586").toBuilder()
                .externalOrderId("OD1").content("본문").platformStatus("SND").title("배송 지연 문의").build();
        given(customerInquiryRepository.findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(9L,
                InquiryType.CALL_CENTER, "11586")).willReturn(Optional.of(existing));
        InquiryRecord untitled = new InquiryRecord(InquiryType.CALL_CENTER, "11586", null, "OD1", null, null, "본문",
                null, InquiryStatus.ANSWERED, "SND", NOW, null, List.of());

        upserter.upsert(account, untitled, linker);

        verify(customerInquiryRepository, times(2)).save(saved.capture());
        CustomerInquiry updated = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(updated.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(updated.getTitle()).isEqualTo("배송 지연 문의");
    }
}
