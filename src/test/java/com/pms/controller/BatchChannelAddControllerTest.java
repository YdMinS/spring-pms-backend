package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CommissionRate;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductCategory;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Package;
import com.pms.domain.Product;
import com.pms.domain.Role;
import com.pms.domain.Seller;
import com.pms.domain.User;
import com.pms.dto.request.BatchChannelAddRequest;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.MasterProductCategoryRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.RefreshTokenRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.UserRepository;
import com.pms.security.TenantContext;
import com.pms.service.ImageStorageService;
import com.pms.service.ProductImageLoader;
import com.pms.service.ThumbnailRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch channel-add endpoint (FEATURE_2608_06 / 15): authority (401/403/200) + partial-success with a
 * <em>DB assertion</em> that a failed target writes nothing while a succeeding one commits its cell.
 *
 * <p>⚠️ Intentionally NOT {@code @Transactional} (same reason as {@code ChannelAddControllerTest}):
 * each {@code addChannel} runs {@code REQUIRES_NEW} and commits independently, so a rolling-back test
 * transaction would hide seeds and the committed cells. Seeds are committed; cleanup is tenant-scoped
 * {@code deleteAll}. The failed target here is a missing master category (pre-save 400), so it writes
 * nothing — this proves <em>partial success</em>, not REQUIRES_NEW rollback isolation (which is CUT).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BatchChannelAddControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CommissionRateRepository commissionRateRepository;
    @Autowired private MarginPolicyRepository marginPolicyRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductCategoryRepository masterProductCategoryRepository;
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private CarrierRateRepository carrierRateRepository;
    @Autowired private PackageRepository packageRepository;

    @MockBean private ThumbnailRenderer thumbnailRenderer;
    @MockBean private ImageStorageService imageStorageService;
    @MockBean private ProductImageLoader productImageLoader;

    private static final String ADMIN_EMAIL = "admin@batchchannel.com";
    private static final String USER_EMAIL = "user@batchchannel.com";
    private static final String PASSWORD = "testpass123";

    private String adminToken;
    private String userToken;
    private Long masterId;
    private Long seller1Id;
    private Long seller2Id;

    private String batchUrl() {
        return "/api/admin/master-products/" + masterId + "/listings/batch";
    }

    @BeforeEach
    void seed() throws Exception {
        TenantContext.set(1L);

        userRepository.save(User.builder().email(ADMIN_EMAIL).password(passwordEncoder.encode(PASSWORD))
                .name("Admin").role(Role.ADMIN).build());
        userRepository.save(User.builder().email(USER_EMAIL).password(passwordEncoder.encode(PASSWORD))
                .name("User").role(Role.USER).build());

        Seller seller1 = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        seller1Id = seller1.getId();
        Seller seller2 = sellerRepository.save(Seller.builder()
                .sellerName("즐거운상회").businessRegistration("222-33-44444").build());
        seller2Id = seller2.getId();

        Product product = productRepository.save(Product.builder()
                .productName("운동화").brand("나이키")
                .price(new BigDecimal("1500")).imageUrl("products/p.jpg").active(true).build());
        Category category = categoryRepository.save(Category.builder()
                .name("신발").platform("COUPANG").platformCategoryId("cat-1").build());

        commissionRateRepository.save(CommissionRate.builder()
                .platform("COUPANG").category(null).rate(new BigDecimal("0.10")).isDefault(true).build());
        // Both sellers need a COUPANG margin preset so the price engine resolves for the all-succeed case.
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller1).platform("COUPANG").marginRate(new BigDecimal("0.1500")).build());
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller2).platform("COUPANG").marginRate(new BigDecimal("0.1500")).build());

        Carrier carrier = carrierRepository.save(Carrier.builder().name("CJ").isActive(true).build());
        CarrierRate delivery = carrierRateRepository.save(CarrierRate.builder()
                .carrier(carrier).type("STANDARD").cost(new BigDecimal("2500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        Package box = packageRepository.save(Package.builder()
                .type("M").cost(new BigDecimal("500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());

        // Master has a category for COUPANG only — NAVER targets fail category pre-validation (400).
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true)
                .defaultDelivery(delivery).defaultPackage(box).build());
        masterId = master.getId();
        masterProductCategoryRepository.save(MasterProductCategory.builder()
                .masterProduct(master).platform("COUPANG").category(category).build());
        MasterProductOption option = masterProductOptionRepository.save(MasterProductOption.builder()
                .masterProduct(master).name("1세트").build());
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(option).product(product).quantity(1).build());

        given(productImageLoader.load(any())).willReturn(new byte[]{1, 2, 3});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{4, 5, 6});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/generated.jpg");

        adminToken = login(ADMIN_EMAIL);
        userToken = login(USER_EMAIL);
    }

    @AfterEach
    void cleanup() {
        TenantContext.set(1L);
        refreshTokenRepository.deleteAll();
        generatedProductDataRepository.deleteAll();
        productListingProductRepository.deleteAll();
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
        masterProductCategoryRepository.deleteAll();
        masterProductOptionItemRepository.deleteAll();
        masterProductOptionRepository.deleteAll();
        masterProductRepository.deleteAll();
        marginPolicyRepository.deleteAll();
        commissionRateRepository.deleteAll();
        categoryRepository.deleteAll();
        productRepository.deleteAll();
        sellerRepository.deleteAll();
        carrierRateRepository.deleteAll();
        carrierRepository.deleteAll();
        packageRepository.deleteAll();
        userRepository.deleteAll();
        TenantContext.clear();
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("token").asText();
    }

    private String body(BatchChannelAddRequest.Target... targets) throws Exception {
        return objectMapper.writeValueAsString(BatchChannelAddRequest.builder()
                .targets(List.of(targets)).build());
    }

    private BatchChannelAddRequest.Target target(Long sellerId, String platform) {
        return BatchChannelAddRequest.Target.builder().sellerId(sellerId).platform(platform).build();
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void batch_noToken_returns401() throws Exception {
        mockMvc.perform(post(batchUrl())
                        .contentType("application/json").content(body(target(seller1Id, "COUPANG"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void batch_userToken_returns403() throws Exception {
        mockMvc.perform(post(batchUrl())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(body(target(seller1Id, "COUPANG"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void batch_adminToken_bothTargetsSucceed_returns200() throws Exception {
        mockMvc.perform(post(batchUrl())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(body(target(seller1Id, "COUPANG"), target(seller2Id, "COUPANG"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.results.length()").value(2));
    }

    // ---- partial success + DB assertion (MUST-KEEP) ----

    @Test
    void batch_oneCategoryUnset_partialSuccess_dbReflectsOnlyTheSucceededCell() throws Exception {
        // seller1 on COUPANG (has category) succeeds; seller2 on NAVER (no master category) fails 400.
        mockMvc.perform(post(batchUrl())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(body(target(seller1Id, "COUPANG"), target(seller2Id, "NAVER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failed").value(1));

        // DB truth: the succeeding target committed its cell; the failing target wrote nothing.
        TenantContext.set(1L);
        assertThat(productListingRepository
                .existsByMasterProductIdAndSellerIdAndPlatform(masterId, seller1Id, "COUPANG")).isTrue();
        assertThat(productListingRepository
                .existsByMasterProductIdAndSellerIdAndPlatform(masterId, seller2Id, "NAVER")).isFalse();
    }
}
