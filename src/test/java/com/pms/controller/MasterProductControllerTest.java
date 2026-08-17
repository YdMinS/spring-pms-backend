package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MasterProduct list + coverage matrix: authority, matrix happy path (account LEFT JOIN listing),
 * and 404 for a missing master.
 *
 * <p>Cross-tenant ownership (a tenant-2 master → 404) is covered by {@code MasterProductTenantIsolationTest}
 * (non-@Transactional): the @Transactional harness resolves one session tenant and cannot seed a second
 * tenant's row, so cross-tenant scoping is proven with the isolation-test pattern instead.</p>
 */
class MasterProductControllerTest extends BaseIntegrationTest {

    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductComponentRepository componentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private SellerRepository sellerRepository;

    private static final String PATH = "/api/admin/master-products";
    private Long masterId;
    private Long productId1;
    private Long productId2;

    @BeforeEach
    void seedMatrix() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());

        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("마스터A").active(true).build());
        masterId = master.getId();

        // Two component products so option-coverage validation has a concrete target set.
        Product product1 = productRepository.save(Product.builder()
                .productName("상품1").name("상품1").build());
        Product product2 = productRepository.save(Product.builder()
                .productName("상품2").name("상품2").build());
        productId1 = product1.getId();
        productId2 = product2.getId();
        componentRepository.save(MasterProductComponent.builder()
                .masterProduct(master).product(product1).build());
        componentRepository.save(MasterProductComponent.builder()
                .masterProduct(master).product(product2).build());

        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG").platformProductId("X").name("리스팅").seller(seller)
                .masterProduct(master).build());
        productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(listing).optionName("기본").sellingPrice(new BigDecimal("1000")).build());

        // Two accounts: COUPANG (registered against the listing) + NAVER (uncovered) → LEFT JOIN shape.
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").accountAlias("메인")
                .vendorId("A001").accessKey("ak").secretKey("sk").isActive(true).build());
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("NAVER").accountAlias("네이버")
                .vendorId("A002").accessKey("ak").secretKey("sk").isActive(true).build());
    }

    @Test
    void matrix_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH + "/" + masterId + "/matrix"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void matrix_userToken_returns403() throws Exception {
        mockMvc.perform(get(PATH + "/" + masterId + "/matrix")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void matrix_adminToken_returnsAccountsLeftJoinedWithListing() throws Exception {
        mockMvc.perform(get(PATH + "/" + masterId + "/matrix")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.masterId").value(masterId))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                // COUPANG account is registered with a cell; NAVER account is not.
                .andExpect(jsonPath("$.data.rows[?(@.platform=='COUPANG')].registered").value(true))
                .andExpect(jsonPath("$.data.rows[?(@.platform=='COUPANG')].cell.platformProductId").value("X"))
                .andExpect(jsonPath("$.data.rows[?(@.platform=='NAVER')].registered").value(false));
    }

    @Test
    void matrix_missingMaster_returns404() throws Exception {
        mockMvc.perform(get(PATH + "/999999/matrix")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void list_adminToken_returns200() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ------------------------------------------------------------- create master authority

    @Test
    void create_noToken_returns401() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(createMasterBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createMasterBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_adminToken_returns201() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createMasterBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.components.length()").value(2));
    }

    // ------------------------------------------------------------- create option (coverage validation)

    @Test
    void createOption_fullCoverage_returns200() throws Exception {
        String body = "{\"name\":\"2세트\",\"items\":["
                + "{\"productId\":" + productId1 + ",\"quantity\":2},"
                + "{\"productId\":" + productId2 + ",\"quantity\":2}]}";
        mockMvc.perform(post(PATH + "/" + masterId + "/options")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void createOption_missingComponent_returns400() throws Exception {
        // items omit productId2 → subset of the component set
        String body = "{\"name\":\"불완전\",\"items\":[{\"productId\":" + productId1 + ",\"quantity\":1}]}";
        mockMvc.perform(post(PATH + "/" + masterId + "/options")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    void update_resendSameComponents_returns200_noUniqueViolation() throws Exception {
        // Regression: PATCH re-sending an unchanged component set does delete + re-insert of the same
        // (master, product) pairs in one transaction. Without a flush between them, Hibernate runs the
        // INSERTs before the entity DELETEs and hits the UQ_MPC unique index (was a 500).
        String body = "{\"componentProductIds\":[" + productId1 + "," + productId2 + "]}";
        mockMvc.perform(patch(PATH + "/" + masterId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.components.length()").value(2));
    }

    private String createMasterBody() {
        return "{\"name\":\"신규마스터\",\"componentProductIds\":[" + productId1 + "," + productId2 + "]}";
    }
}
