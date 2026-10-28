package com.pms.service.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.GrandTotalSend;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.MasterProductService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.listing.category.CategoryMetaSchema;
import com.pms.service.listing.category.CoupangCategoryMeta;
import com.pms.service.listing.shipping.CoupangShippingConfigResolver;
import com.pms.service.listing.shipping.ResolvedShippingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * FEATURE_2610_34: 총 수량 retry of a new Coupang registration (D1 · D2 · D3 · D4 · D5 · D8 · D21 · D24) and of a
 * revision (D6 · D11 · D16 · D23). The HTTP client is mocked; ObjectMapper is real.
 */
@ExtendWith(MockitoExtension.class)
class CoupangListingAdapterGrandTotalTest {

    private static final String INVALID_OPTION_BODY =
            "{\"code\":\"ERROR\",\"message\":\"유효하지 않은 구매 옵션이 존재합니다.\",\"data\":null}";
    private static final String REQUIRED_BODY =
            "{\"code\":\"ERROR\",\"message\":\"[2의 필수 옵션이(총 수량) 입력되지 않았습니다.]\"}";

    @Mock private CoupangApiClient client;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private CoupangCategoryMeta metaAdapter;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private TagMergeService tagMergeService;
    @Mock private RegistrationNameGenerator registrationNameGenerator;
    @Mock private OptionCheckSuffixResolver optionCheckSuffixResolver;
    @Mock private MasterProductService masterProductService;
    @Mock private CoupangShippingConfigResolver shippingConfigResolver;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CoupangListingAdapter adapter;

    private final GeneratedProductData gen = GeneratedProductData.builder()
            .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();

    @BeforeEach
    void defaults() {
        lenient().when(metaAdapter.getMeta(any(), anyString())).thenReturn(new CategoryMetaSchema(List.of(), List.of()));
        lenient().when(shippingConfigResolver.resolve(any())).thenReturn(new ResolvedShippingConfig(
                "OUT-1",
                "RC-1", "반품담당", "021234567", "06000", "서울시", "1층",
                new BigDecimal("2500"), new BigDecimal("2500"),
                "SEQUENCIAL", "KGB", "FREE", new BigDecimal("0"), null, "N", "NOT_UNION_DELIVERY",
                null));
        // One active option that stores 수량 and 총 수량 (the cell option is the merge source — no master).
        lenient().when(productListingOptionRepository.findByProductListingId(100L)).thenReturn(List.of(
                ProductListingOption.builder().id(1L).optionName("2개").sellingPrice(new BigDecimal("6000"))
                        .active(true).categoryAttributes(new java.util.LinkedHashMap<>(Map.of("수량", "2", "총 수량", "2")))
                        .build()));
    }

    private PlatformCategory category(boolean remembered) {
        PlatformCategory category = PlatformCategory.builder().id(72931L).platform(Platform.COUPANG).code("72931")
                .name("기타유가공품").grandTotalOmitted(remembered).build();
        given(masterChannelConfigService.resolveChannelCategory(any()))
                .willReturn(new MasterChannelConfigService.ChannelCategory(category, false));
        return category;
    }

    private ProductListing cell(GrandTotalSend state) {
        return ProductListing.builder().id(100L).platform(Platform.COUPANG).name("셀")
                .platformProductId("123456789").grandTotalSend(state).build();
    }

    private MarketplaceAccount acct() {
        return MarketplaceAccountFixture.coupangStubBuilder("V1", "wing-user").isActive(true).build();
    }

    private static HttpClientErrorException badRequest(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    // D1 · D2 · D4: sent with 총 수량, rejected with the invalid-option text → second POST without it → remembered.
    @Test
    void register_invalidOptionRejection_retriesWithoutGrandTotal_andRemembers() {
        PlatformCategory category = category(false);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any()))
                .willReturn(INVALID_OPTION_BODY, "{\"code\":\"SUCCESS\",\"data\":555}");

        PushOutcome outcome = adapter.registerAndReport(cell(GrandTotalSend.SENT), gen, acct());

        assertThat(outcome.platformProductId()).isEqualTo("555");
        assertThat(outcome.grandTotalSend()).isEqualTo(GrandTotalSend.OMITTED);
        assertThat(outcome.learnedCategory()).isSameAs(category);
        assertThat(outcome.learnedGrandTotalOmitted()).isTrue();
        assertThat(payload.getAllValues()).hasSize(2);
        assertThat(payload.getAllValues().get(0)).contains("총 수량");
        assertThat(payload.getAllValues().get(1)).doesNotContain("총 수량").contains("\"수량\"");
    }

    // D21: the same rejection as a 400 body retries too; D5 · D24: a rejected retry → 400 with the second message.
    @Test
    void register_retryWithoutGrandTotalRejected_throws400WithSecondMessage() {
        category(false);
        given(client.post(anyString(), anyString(), any()))
                .willThrow(badRequest(INVALID_OPTION_BODY))
                .willReturn("{\"code\":\"ERROR\",\"message\":\"두 번째 거부\",\"data\":null}");

        assertThatThrownBy(() -> adapter.registerAndReport(cell(GrandTotalSend.SENT), gen, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("총 수량을 빼고도 다시 시도했지만 쿠팡이 거부했습니다: 두 번째 거부");
    }

    // D3 · D8 · D22: a remembered category sends without 총 수량 first; a missing-총 수량 rejection puts it back,
    // a pass forgets the memory and the listing is SENT.
    @Test
    void register_rememberedCategory_requiredRejection_reAddsAndForgets() {
        PlatformCategory category = category(true);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.post(anyString(), payload.capture(), any()))
                .willThrow(badRequest(REQUIRED_BODY))
                .willReturn("{\"code\":\"SUCCESS\",\"data\":556}");

        PushOutcome outcome = adapter.registerAndReport(cell(GrandTotalSend.SENT), gen, acct());

        assertThat(outcome.grandTotalSend()).isEqualTo(GrandTotalSend.SENT);
        assertThat(outcome.learnedCategory()).isSameAs(category);
        assertThat(outcome.learnedGrandTotalOmitted()).isFalse();
        assertThat(payload.getAllValues().get(0)).doesNotContain("총 수량");
        assertThat(payload.getAllValues().get(1)).contains("총 수량");
    }

    // D3 · D18: a remembered category that passes on the first try → OMITTED, memory untouched.
    @Test
    void register_rememberedCategory_passesWithoutGrandTotal() {
        category(true);
        given(client.post(anyString(), anyString(), any())).willReturn("{\"data\":557}");

        PushOutcome outcome = adapter.registerAndReport(cell(GrandTotalSend.SENT), gen, acct());

        assertThat(outcome.grandTotalSend()).isEqualTo(GrandTotalSend.OMITTED);
        assertThat(outcome.learnedCategory()).isNull();
        verify(client, times(1)).post(anyString(), anyString(), any());
    }

    // D4: a unit rejection ("… 값 혹은 단위 …") is not retried — thrown as before.
    @Test
    void register_unitRejection_notRetried() {
        category(false);
        given(client.post(anyString(), anyString(), any())).willReturn(
                "{\"code\":\"ERROR\",\"message\":\"유효하지 않은 구매 옵션 값 혹은 단위가 존재합니다.\",\"data\":null}");

        assertThatThrownBy(() -> adapter.registerAndReport(cell(GrandTotalSend.SENT), gen, acct()))
                .isInstanceOf(IllegalStateException.class);
        verify(client, times(1)).post(anyString(), anyString(), any());
    }

    // D11 · D23: a SENT listing in a remembered category revises without 총 수량; a plain 2xx → OMITTED.
    @Test
    void update_sentListingRememberedCategory_omitsAndReportsOmitted() {
        category(true);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.put(anyString(), payload.capture(), any())).willReturn("{\"code\":\"SUCCESS\"}");

        PushOutcome outcome = adapter.updateAndReport(cell(GrandTotalSend.SENT), gen, acct());

        assertThat(outcome.grandTotalSend()).isEqualTo(GrandTotalSend.OMITTED);
        assertThat(outcome.learnedCategory()).isNull();
        assertThat(payload.getValue()).doesNotContain("총 수량");
    }

    // D16 · D23: an OMITTED listing rejected (2xx body) for a missing 총 수량 → PUT again with it → SENT_CONFIRMED.
    @Test
    void update_omittedListingRequiredRejection_reAddsAndConfirms() {
        category(true);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.put(anyString(), payload.capture(), any()))
                .willReturn(REQUIRED_BODY, "{\"code\":\"SUCCESS\"}");

        PushOutcome outcome = adapter.updateAndReport(cell(GrandTotalSend.OMITTED), gen, acct());

        assertThat(outcome.grandTotalSend()).isEqualTo(GrandTotalSend.SENT_CONFIRMED);
        assertThat(payload.getAllValues().get(0)).doesNotContain("총 수량");
        assertThat(payload.getAllValues().get(1)).contains("총 수량");
    }

    // D24: the PUT with 총 수량 put back is rejected too → 400 with its message, nothing reported.
    @Test
    void update_reAddRejected_throws400WithSecondMessage() {
        category(false);
        given(client.put(anyString(), anyString(), any()))
                .willThrow(badRequest(REQUIRED_BODY))
                .willThrow(badRequest("{\"code\":\"ERROR\",\"message\":\"두 번째 거부\"}"));

        assertThatThrownBy(() -> adapter.updateAndReport(cell(GrandTotalSend.OMITTED), gen, acct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("총 수량을 다시 넣고도 다시 시도했지만 쿠팡이 거부했습니다: 두 번째 거부");
    }

    // D11: a SENT_CONFIRMED listing always sends 총 수량 — one PUT, even in a remembered category.
    @Test
    void update_confirmedListing_sendsGrandTotalOnce() {
        category(true);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(client.put(anyString(), payload.capture(), any())).willReturn("{\"code\":\"SUCCESS\"}");

        PushOutcome outcome = adapter.updateAndReport(cell(GrandTotalSend.SENT_CONFIRMED), gen, acct());

        assertThat(outcome.grandTotalSend()).isNull();
        assertThat(payload.getValue()).contains("총 수량");
        verify(client, times(1)).put(anyString(), anyString(), any());
        // Only the payload build resolves the category — the memory is not consulted for a confirmed listing.
        verify(masterChannelConfigService, times(1)).resolveChannelCategory(any());
    }
}
