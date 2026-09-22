package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
 *
 * <p>2609_71/D7: 셀 <b>직접 등록</b>(POST)은 사라졌다 — 판매상품은 마스터를 통해서만 생긴다.</p>
 */
class ProductListingControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductRepository productRepository;
    // ⚠️ actuator 가 controllerEndpointHandlerMapping 도 올려서 타입만으로는 두 개다 — 이름으로 고른다.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

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
                .platform(Platform.COUPANG).platformProductId("P-LINKED").name("연결된 셀")
                .status(ListingStatus.SELLING).seller(seller).masterProduct(master).build()).getId();
        unlinkedId = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).platformProductId("P-UNLINKED").name("미연결 셀")
                .status(ListingStatus.SELLING).seller(seller).build()).getId();
    }

    /** ⚠️ 옵션·구성은 필수 필드다 — 빼면 bean validation 400 이 떠서 D32 차단을 검증한 것이 아니게 된다. */
    private String updateBody(String platformProductId, String name) throws Exception {
        return objectMapper.writeValueAsString(CreateProductListingRequest.builder()
                .sellerId(sellerId).platform("COUPANG")
                .platformProductId(platformProductId).name(name)
                // 2609_71/D8: 옵션 요청에 구성품(products) 필드는 없다 — 구성품은 마스터가 갖는다.
                .options(List.of(CreateProductListingRequest.OptionRequest.builder()
                        .optionName("6입").sellingPrice(new BigDecimal("5900"))
                        .build()))
                .build());
    }

    /**
     * 2609_71/D7: 직접 등록 엔드포인트가 정말 사라졌는가.
     *
     * <p>🔴 상태 코드로 보지 않는다 — {@code GlobalExceptionHandler} 의 catch-all 이
     * {@code HttpRequestMethodNotSupportedException} 까지 500 으로 덮어 405 가 나오지 않는다. 전체 컨텍스트의
     * 핸들러 매핑을 직접 뒤지는 쪽이 정확하고, 누가 POST 를 되살리면 그 순간 깨진다.</p>
     */
    @Test
    void testCreateListingEndpointGone() {
        boolean mapped = handlerMapping.getHandlerMethods().keySet().stream()
                .anyMatch(info -> info.getMethodsCondition().getMethods().contains(RequestMethod.POST)
                        && info.getPatternValues().contains(BASE));

        org.assertj.core.api.Assertions.assertThat(mapped).isFalse();
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
