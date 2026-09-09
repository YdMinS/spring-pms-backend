package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 원가 파급 API 통합 테스트 — ADMIN 권한(401/403/200) + 매입 → 마스터 → 셀 경로가 실제 스키마 위에서
 * 이어지는지.
 *
 * <p>확정(apply)은 <b>셀이 없는 마스터</b>로 검증한다: 셀 파급은 {@code REQUIRES_NEW} 라 롤백되는 테스트
 * 트랜잭션의 미커밋 시드를 보지 못한다(문서화된 함정). 위임과 집계가 여기서 검증할 몫이고, 셀 단위 동작은
 * 기존 {@code MasterPropagationService} 테스트가 담당한다.
 */
class CostPropagationControllerTest extends BaseIntegrationTest {

    private static final String PREVIEW = "/api/admin/cost/propagation/preview";
    private static final String APPLY = "/api/admin/cost/propagation/apply";
    private static final String DEVIATION = "/api/admin/cost/deviation";

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;

    private Long masterId;
    private Long emptyMasterId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());

        Product product = productRepository.save(
                Product.builder().productName("양말A").price(new BigDecimal("3000.00")).build());

        // 기준가를 움직이는 매입 1건 = 파급 대상의 출발점.
        purchaseRecordRepository.save(PurchaseRecord.builder()
                .product(product).seller(seller).purchasedOn(LocalDate.now())
                .quantity(3).totalAmount(new BigDecimal("12000.00")).unitPrice(new BigDecimal("4000.0000"))
                .reflectToBasePrice(true).build());
        // 프로모션 매입: 기준가를 안 건드렸으므로 파급 대상도 괴리 목록도 아니다.
        purchaseRecordRepository.save(PurchaseRecord.builder()
                .product(product).seller(seller).purchasedOn(LocalDate.now())
                .quantity(3).totalAmount(new BigDecimal("1500.00")).unitPrice(new BigDecimal("500.0000"))
                .reflectToBasePrice(false).build());

        MasterProduct master = masterProductRepository.save(
                MasterProduct.builder().name("양말 마스터").active(true).build());
        masterId = master.getId();
        MasterProductOption masterOption = masterProductOptionRepository.save(
                MasterProductOption.builder().masterProduct(master).name("3켤레").build());
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(masterOption).product(product).quantity(2).build());

        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).name("쿠팡 셀").status(ListingStatus.SELLING)
                .platformProductId("CP-1").seller(seller).masterProduct(master).build());
        productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName("3켤레").sellingPrice(new BigDecimal("6000"))
                .masterProductOption(masterOption).build());
        // 자산이 있어야 파급(재생성) 대상이다 — 없으면 propagate 가 건너뛴다.
        generatedProductDataRepository.save(GeneratedProductData.builder()
                .productListing(cell).source(GeneratedContentSource.AUTO).build());

        emptyMasterId = masterProductRepository.save(
                MasterProduct.builder().name("셀 없는 마스터").active(true).build()).getId();
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void testPreviewWithoutTokenUnauthorized() throws Exception {
        mockMvc.perform(get(PREVIEW)).andExpect(status().isUnauthorized());
    }

    @Test
    void testPreviewWithUserTokenForbidden() throws Exception {
        mockMvc.perform(get(PREVIEW).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void testApplyWithUserTokenForbidden() throws Exception {
        mockMvc.perform(post(APPLY).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masterIds\":[" + emptyMasterId + "]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testApplyWithoutTokenUnauthorized() throws Exception {
        mockMvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masterIds\":[" + emptyMasterId + "]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testDeviationWithUserTokenForbidden() throws Exception {
        mockMvc.perform(get(DEVIATION).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ---- happy path ----

    @Test
    void testPreviewReturnsAffectedMasterAndCell() throws Exception {
        mockMvc.perform(get(PREVIEW).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalMasters").value(1))
                .andExpect(jsonPath("$.data.totalCells").value(1))
                .andExpect(jsonPath("$.data.masters[0].masterId").value(masterId))
                .andExpect(jsonPath("$.data.masters[0].masterName").value("양말 마스터"))
                .andExpect(jsonPath("$.data.masters[0].optionCount").value(1))
                .andExpect(jsonPath("$.data.skipped").isEmpty());
    }

    @Test
    void testPreviewWithNarrowSinceFindsNothing() throws Exception {
        // 매입은 오늘 것뿐이라 내일 이후로 창을 좁히면 대상이 없다.
        mockMvc.perform(get(PREVIEW).param("since", LocalDate.now().plusDays(1).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalMasters").value(0))
                .andExpect(jsonPath("$.data.masters").isEmpty());
    }

    @Test
    void testApplyDelegatesAndReportsCounts() throws Exception {
        mockMvc.perform(post(APPLY).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masterIds\":[" + emptyMasterId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.requestedMasters").value(1))
                .andExpect(jsonPath("$.data.propagatedCells").value(0))
                .andExpect(jsonPath("$.data.results[0].masterId").value(emptyMasterId));
    }

    @Test
    void testApplyWithEmptyMasterIdsBadRequest() throws Exception {
        mockMvc.perform(post(APPLY).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"masterIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDeviationExcludesPromotionalPurchase() throws Exception {
        // 기준가 3000 · 최근 반영 매입 4000 → 괴리율 0.3333. 프로모션(500)은 목록에 없다.
        mockMvc.perform(get(DEVIATION).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].productName").value("양말A"))
                .andExpect(jsonPath("$.data[0].latestPurchasePrice").value(4000.0000))
                .andExpect(jsonPath("$.data[0].diffRate").value(0.3333));
    }
}
