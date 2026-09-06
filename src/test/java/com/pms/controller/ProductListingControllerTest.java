package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.dto.request.CreateProductListingRequest;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * legacy 판매상품 CRUD(`/api/product-listings`)에 2609_22/04 가 얹은 두 가지: 마스터 연결 여부 필터와
 * <b>D32 쓰기 차단</b>.
 *
 * <p>D32 는 양방향으로 고정한다 — 연결된 셀의 legacy 수정은 400(이 경로가 옵션을 전부 delete + recreate 해
 * 마스터 FK·옵션 id·승인상태를 날린다), 미연결 셀은 계속 200(마켓 상품 ID 오타를 고칠 유일한 창구).</p>
 */
class ProductListingControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductRepository productRepository;

    private static final String BASE = "/api/product-listings";

    private Long sellerId;
    private Long linkedId;
    private Long unlinkedId;
    private Long masterId;
    private Long productId;

    @BeforeEach
    void seed() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        sellerId = seller.getId();
        productId = productRepository.save(Product.builder()
                .productName("생수 2L").brand("노브랜드")
                .price(new BigDecimal("700")).imageUrl("products/p.jpg").active(true).build()).getId();
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("생수 마스터").active(true).build());
        masterId = master.getId();

        linkedId = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("P-LINKED").name("연결된 셀")
                .status(ListingStatus.SELLING).seller(seller).masterProduct(master).build()).getId();
        unlinkedId = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("P-UNLINKED").name("미연결 셀")
                .status(ListingStatus.SELLING).seller(seller).build()).getId();
    }

    /** ⚠️ 옵션·구성은 필수 필드다 — 빼면 bean validation 400 이 떠서 D32 차단을 검증한 것이 아니게 된다. */
    private String updateBody(String platformProductId, String name) throws Exception {
        return objectMapper.writeValueAsString(CreateProductListingRequest.builder()
                .sellerId(sellerId).platform("COUPANG")
                .platformProductId(platformProductId).name(name)
                .options(List.of(CreateProductListingRequest.OptionRequest.builder()
                        .optionName("6입").sellingPrice(new BigDecimal("5900"))
                        .products(List.of(CreateProductListingRequest.OptionRequest.ProductRequest.builder()
                                .productId(productId).quantity(6).build()))
                        .build()))
                .build());
    }

    // ---- master-link filter (05 가 이 3가지에 전부 의존한다) ----

    @Test
    void testListMasterLinkedFilter() throws Exception {
        // 미연결만
        mockMvc.perform(get(BASE).param("platform", "COUPANG").param("masterLinked", "false")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(unlinkedId))
                .andExpect(jsonPath("$.data.content[0].masterProductId").doesNotExist());

        // 연결된 것만
        mockMvc.perform(get(BASE).param("platform", "COUPANG").param("masterLinked", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(linkedId))
                .andExpect(jsonPath("$.data.content[0].masterProductId").value(masterId));

        // 미지정 = 기존 동작(전체)
        mockMvc.perform(get(BASE).param("platform", "COUPANG")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));
    }

    // ---- D32: legacy 쓰기 차단 (양방향) ----

    @Test
    void testUpdateLinkedListing400() throws Exception {
        mockMvc.perform(patch(BASE + "/" + linkedId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(updateBody("P-LINKED", "이름 변경 시도")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUpdateUnlinkedListing200() throws Exception {
        mockMvc.perform(patch(BASE + "/" + unlinkedId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(updateBody("P-UNLINKED-FIXED", "미연결 셀 수정")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platformProductId").value("P-UNLINKED-FIXED"));
    }
}
