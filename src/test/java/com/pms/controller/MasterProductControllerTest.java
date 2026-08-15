package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private SellerRepository sellerRepository;

    private static final String PATH = "/api/admin/master-products";
    private Long masterId;

    @BeforeEach
    void seedMatrix() {
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());

        MasterProduct master = masterProductRepository.save(MasterProduct.builder().name("마스터A").build());
        masterId = master.getId();

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
}
