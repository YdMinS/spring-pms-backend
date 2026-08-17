package com.pms.service.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Category;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * Coupang adapter (FEATURE_2608_06 / 3c): register returns the parsed sellerProductId; fetchStatus maps the
 * market statusName → ListingStatus and parses per-option ids. ObjectMapper is real; the HTTP client and the
 * option repo are mocked.
 */
@ExtendWith(MockitoExtension.class)
class CoupangListingAdapterTest {

    @Mock private com.pms.service.coupang.CoupangApiClient client;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CoupangListingAdapter adapter;

    private ProductListing cell() {
        return ProductListing.builder().id(100L).platform("COUPANG").name("셀")
                .platformProductId("123456789")
                .category(Category.builder().platformCategoryId("cat-1").build())
                .build();
    }

    private MarketplaceAccount acct() {
        return MarketplaceAccount.builder().vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build();
    }

    @Test
    void register_parsesSellerProductIdFromResponse() {
        lenient().when(productListingOptionRepository.findByProductListingId(100L))
                .thenReturn(List.of(ProductListingOption.builder().optionName("1세트")
                        .sellingPrice(new BigDecimal("6000")).build()));
        GeneratedProductData gen = GeneratedProductData.builder()
                .thumbnailUrl("https://s3/thumb.jpg").detailHtml("<p>셀</p>").build();
        // Category now comes from the master (master × platform), not the cell.
        given(masterChannelConfigService.resolveCategory(any()))
                .willReturn(Category.builder().platformCategoryId("cat-1").build());
        given(client.post(anyString(), anyString(), any()))
                .willReturn("{\"code\":\"SUCCESS\",\"data\":987654321}");

        String sellerProductId = adapter.register(cell(), gen, acct());

        assertThat(sellerProductId).isEqualTo("987654321");
    }

    @Test
    void fetchStatus_approved_mapsSellingAndOptionIds() {
        given(client.get(anyString(), eq(""), any())).willReturn(
                "{\"code\":\"SUCCESS\",\"data\":{\"statusName\":\"승인완료\","
                        + "\"items\":[{\"itemName\":\"1세트\",\"vendorItemId\":111,\"sellerProductItemId\":222}]}}");

        FetchResult result = adapter.fetchStatus(cell(), acct());

        assertThat(result.status()).isEqualTo(ListingStatus.SELLING);
        assertThat(result.options()).hasSize(1);
        assertThat(result.options().get(0).optionName()).isEqualTo("1세트");
        assertThat(result.options().get(0).vendorItemId()).isEqualTo("111");
        assertThat(result.options().get(0).sellerProductItemId()).isEqualTo("222");
    }

    @Test
    void fetchStatus_underReview_mapsSubmitted() {
        given(client.get(anyString(), eq(""), any())).willReturn(
                "{\"code\":\"SUCCESS\",\"data\":{\"statusName\":\"심사중\",\"items\":[]}}");

        FetchResult result = adapter.fetchStatus(cell(), acct());

        assertThat(result.status()).isEqualTo(ListingStatus.SUBMITTED);
        assertThat(result.options()).isEmpty();
    }
}
