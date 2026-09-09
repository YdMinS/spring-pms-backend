package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.PriceChangeLog;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.PriceTargetType;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가격 변경 이력 조회 API — ADMIN 권한(401/403/200) + 채널 정보가 <b>컬럼이 아니라 조인</b>으로 나오는지.
 *
 * <p>🔴 핵심 = {@code masterProductId} 로 조회하면 쿠팡·네이버 셀의 판매가 변동이 <b>한 목록</b>에 나오는 것.
 * "쿠팡은 올렸는데 네이버는 그대로"를 볼 수 있느냐가 이 화면의 존재 이유다.
 */
class PriceHistoryControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/price-history";

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private PriceChangeLogRepository priceChangeLogRepository;

    private Long masterId;
    private Long coupangListingId;
    private Long naverListingId;
    private Long productId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        Product product = productRepository.save(
                Product.builder().productName("양말A").price(new BigDecimal("4000.00")).build());
        productId = product.getId();

        MasterProduct master = masterProductRepository.save(
                MasterProduct.builder().name("양말 마스터").active(true).build());
        masterId = master.getId();

        ProductListing coupang = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).name("쿠팡 셀").status(ListingStatus.SELLING)
                .seller(seller).masterProduct(master).build());
        coupangListingId = coupang.getId();
        ProductListing naver = productListingRepository.save(ProductListing.builder()
                .platform(Platform.NAVER).name("네이버 셀").status(ListingStatus.SELLING)
                .seller(seller).masterProduct(master).build());
        naverListingId = naver.getId();

        ProductListingOption coupangOption = productListingOptionRepository.save(
                ProductListingOption.builder().productListing(coupang).optionName("3켤레")
                        .sellingPrice(new BigDecimal("7000")).build());
        ProductListingOption naverOption = productListingOptionRepository.save(
                ProductListingOption.builder().productListing(naver).optionName("3켤레")
                        .sellingPrice(new BigDecimal("6000")).build());

        // 원가 1행 + 채널 판매가 2행(쿠팡은 올랐고 네이버는 그대로였다가 내렸다).
        priceChangeLogRepository.save(PriceChangeLog.builder()
                .targetType(PriceTargetType.PRODUCT_COST).product(product)
                .oldPrice(new BigDecimal("3000.0000")).newPrice(new BigDecimal("4000.0000"))
                .reason(PriceChangeReason.PURCHASE_UPDATE).createdBy(ADMIN_EMAIL).build());
        priceChangeLogRepository.save(PriceChangeLog.builder()
                .targetType(PriceTargetType.LISTING_SELLING).listingOption(coupangOption)
                .oldPrice(new BigDecimal("6000.0000")).newPrice(new BigDecimal("7000.0000"))
                .reason(PriceChangeReason.PROPAGATION).createdBy(ADMIN_EMAIL).build());
        priceChangeLogRepository.save(PriceChangeLog.builder()
                .targetType(PriceTargetType.LISTING_SELLING).listingOption(naverOption)
                .oldPrice(new BigDecimal("6500.0000")).newPrice(new BigDecimal("6000.0000"))
                .reason(PriceChangeReason.MANUAL).createdBy(ADMIN_EMAIL).build());
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void testHistoryWithoutTokenUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void testHistoryWithUserTokenForbidden() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void testHistoryWithAdminTokenReturnsRows() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    // ---- filters ----

    @Test
    void testFilterByListingReturnsOnlyThatCell() throws Exception {
        mockMvc.perform(get(PATH).param("listingId", String.valueOf(coupangListingId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].listingId").value(coupangListingId));
    }

    @Test
    void testFilterByMasterReturnsAllChannels() throws Exception {
        // 🔴 채널별 비교의 근거 — 쿠팡·네이버 행이 한 목록에 함께 나온다.
        mockMvc.perform(get(PATH).param("masterProductId", String.valueOf(masterId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].platform").value(
                        org.hamcrest.Matchers.containsInAnyOrder("COUPANG", "NAVER")));
    }

    @Test
    void testFilterByPlatformNarrowsToOneChannel() throws Exception {
        mockMvc.perform(get(PATH).param("masterProductId", String.valueOf(masterId))
                        .param("platform", "NAVER")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].listingId").value(naverListingId));
    }

    @Test
    void testFilterByTargetTypeSeparatesCostAndSelling() throws Exception {
        mockMvc.perform(get(PATH).param("targetType", "PRODUCT_COST")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].productId").value(productId));

        mockMvc.perform(get(PATH).param("targetType", "LISTING_SELLING")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void testFilterByPeriodExcludesOutsideRange() throws Exception {
        LocalDate today = LocalDate.now();
        // 오늘까지 포함(상한은 배타적으로 변환되므로 오늘 늦게 생긴 행도 들어온다).
        mockMvc.perform(get(PATH).param("from", today.toString()).param("to", today.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(get(PATH)
                        .param("from", today.minusDays(10).toString())
                        .param("to", today.minusDays(5).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---- view shape (채널 정보는 컬럼이 아니라 조인으로 나온다) ----

    @Test
    void testSellingPriceViewCarriesChannel() throws Exception {
        mockMvc.perform(get(PATH).param("listingId", String.valueOf(coupangListingId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].targetType").value("LISTING_SELLING"))
                .andExpect(jsonPath("$.data[0].listingName").value("쿠팡 셀"))
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"))
                .andExpect(jsonPath("$.data[0].masterProductId").value(masterId))
                .andExpect(jsonPath("$.data[0].optionName").value("3켤레"))
                .andExpect(jsonPath("$.data[0].diff").value(1000))
                .andExpect(jsonPath("$.data[0].createdBy").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.data[0].productId").doesNotExist());
    }

    @Test
    void testProductCostViewHasNoChannel() throws Exception {
        // PRODUCT_COST 행은 채널에 속하지 않는다 — 네 필드가 전부 비는 것이 정상이다.
        mockMvc.perform(get(PATH).param("productId", String.valueOf(productId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].productName").value("양말A"))
                .andExpect(jsonPath("$.data[0].listingId").doesNotExist())
                .andExpect(jsonPath("$.data[0].listingName").doesNotExist())
                .andExpect(jsonPath("$.data[0].platform").doesNotExist())
                .andExpect(jsonPath("$.data[0].masterProductId").doesNotExist())
                .andExpect(jsonPath("$.data[0].optionId").doesNotExist());
    }
}
